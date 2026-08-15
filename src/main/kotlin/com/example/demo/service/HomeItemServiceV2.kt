package com.example.demo.service

import com.example.demo.adapter.CoreBankAdapter
import com.example.demo.adapter.OpenBankingAdapter
import com.example.demo.adapter.UserLogRepository
import com.example.demo.config.AsyncConfig.Companion.LOG_TASK_EXECUTOR
import com.example.demo.config.AsyncConfig.Companion.QUERY_TASK_EXECUTOR
import com.example.demo.domain.HomeItem
import com.example.demo.domain.OpenBankBalance
import com.example.demo.domain.UserEvent
import com.example.demo.repository.HomeItemInfoRepository
import com.example.demo.vo.HomeCardColor
import com.example.demo.vo.HomeCardImage
import com.example.demo.vo.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

private val log = LoggerFactory.getLogger(HomeItemServiceV2::class.java)

/**
 * 2단계. blocking MVC 환경에서 CompletableFuture + Executor 로 병렬/비동기 적용.
 *
 * 1단계([HomeItemService]) 대비 달라지는 지점은 두 곳이다.
 *
 * 1. 개인화 DB 조회와 오픈뱅킹 잔액 조회는 서로 의존이 없으므로 **병렬** 실행한다.
 *    (200ms + 500ms = 700ms  ->  max(200ms, 500ms) = 500ms)
 * 2. 홈 화면 접속 기록 적재는 응답에 쓰이지 않으므로 **비동기** fire-and-forget 으로 던진다.
 *    (700ms  ->  0ms. 응답이 나간 뒤에도 log executor 에서 계속 실행된다.)
 *
 * 전체적으로 1700ms -> 800ms.
 *
 * 다만 코드에 드러나지 않는 것들이 남는다. 발표에서 짚을 지점:
 * - 두 Future 가 현재 HTTP 요청의 자식이라는 관계가 코드에 없다.
 * - 하나가 실패해도 나머지는 계속 실행된다. (취소 전파 없음)
 * - 클라이언트가 연결을 끊어도 executor 의 작업은 끝까지 돈다.
 * - 작업 묶음 전체에 거는 timeout 을 표현할 자리가 없다.
 * - MDC / SecurityContext 는 executor 스레드로 넘어가지 않는다. (3단계 주제)
 */
@Service
class HomeItemServiceV2(
    private val coreBankAdapter: CoreBankAdapter,
    private val homeItemInfoRepository: HomeItemInfoRepository,
    private val openBankingAdapter: OpenBankingAdapter,
    private val userLogRepository: UserLogRepository,
    @Qualifier(QUERY_TASK_EXECUTOR) private val queryTaskExecutor: Executor,
    @Qualifier(LOG_TASK_EXECUTOR) private val logTaskExecutor: Executor,
) {
    fun getHomeItems(userId: UserId): List<HomeItem> {
        // 코어뱅킹에서 계좌 목록 조회 — 이후 두 조회의 입력이므로 병렬화할 수 없다.
        val accounts = coreBankAdapter.getAccounts(userId)
        if (accounts.isEmpty()) {
            return emptyList()
        }

        val accountIds = accounts.map { it.accountId }
        val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }

        // 개인화 DB 에서 계좌 이름 및 색상 조회 (병렬)
        val homeCardInfoFuture = CompletableFuture.supplyAsync(
            { homeItemInfoRepository.getHomeItemInfos(accountIds) },
            queryTaskExecutor,
        )

        // 외부에서 오픈뱅킹 잔액 조회 (병렬)
        val openBankBalanceFuture = if (openBankAccountIds.isEmpty()) {
            CompletableFuture.completedFuture(emptyList<OpenBankBalance>())
        } else {
            CompletableFuture.supplyAsync(
                { openBankingAdapter.getBalances(openBankAccountIds) },
                queryTaskExecutor,
            )
        }

        // 홈 화면 접속 기록 적재 (비동기) — 응답을 기다리게 하지 않는다.
        // 반환된 Future 를 버리므로, 실패는 여기서 직접 잡지 않으면 조용히 사라진다.
        CompletableFuture
            .runAsync({ userLogRepository.saveEvent(userId, UserEvent.GET_HOME) }, logTaskExecutor)
            .exceptionally { throwable ->
                log.warn("홈 화면 접속 기록 적재 실패. userId={}", userId.value, throwable)
                null
            }

        // 두 조회를 함께 기다린다.
        // 하나라도 실패하면 CompletionException 이 던져지고, 나머지 하나는 취소되지 않고 계속 실행된다.
        CompletableFuture.allOf(homeCardInfoFuture, openBankBalanceFuture).join()

        val homeItemInfosByAccountId = homeCardInfoFuture.join().associateBy { it.accountId }
        val openBankBalancesByAccountId = openBankBalanceFuture.join().associateBy { it.accountId }

        // 응답 조립
        return accounts.map {
            val itemInfo = homeItemInfosByAccountId[it.accountId]
            HomeItem(
                accountId = it.accountId,
                accountType = it.accountType,
                balance = if (it.isOpenBank()) {
                    openBankBalancesByAccountId[it.accountId]?.balance
                } else {
                    it.balance
                },
                alias = itemInfo?.alias ?: it.accountType.goodsName,
                color = itemInfo?.color ?: HomeCardColor.DEFAULT_COLOR,
                image = itemInfo?.image ?: HomeCardImage.DEFAULT_IMAGE,
            )
        }
    }
}
