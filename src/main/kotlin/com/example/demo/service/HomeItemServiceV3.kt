package com.example.demo.service

import com.example.demo.adapter.CoreBankAdapter
import com.example.demo.adapter.OpenBankingAdapter
import com.example.demo.adapter.UserLogRepository
import com.example.demo.config.ContextAwareAsyncConfig.Companion.HOME_INFO_TASK_EXECUTOR_V3
import com.example.demo.config.ContextAwareAsyncConfig.Companion.OPEN_BANKING_TASK_EXECUTOR_V3
import com.example.demo.domain.HomeItem
import com.example.demo.domain.UserEvent
import com.example.demo.repository.HomeItemInfoRepository
import com.example.demo.vo.HomeCardColor
import com.example.demo.vo.HomeCardImage
import com.example.demo.vo.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * 3단계. 커스텀 ThreadLocal([com.example.demo.context.CallContext]) 과 MDC 를 worker 스레드로 전파.
 *
 * **[HomeItemServiceV2] 와 비교했을 때 로직은 한 글자도 다르지 않다.**
 * 주입받는 executor 만 decorator 가 붙은 것으로 바뀌었다.
 *
 * 이게 이 단계의 메시지다. 전파는 **호출부의 문제가 아니라 실행 경계의 문제**이고,
 * 실행 경계(executor)에서 한 번 해결하면 호출부는 몰라도 된다.
 *
 * 2단계에서 남겨둔 여섯 가지 중 하나(MDC / 커스텀 컨텍스트)가 여기서 해결된다.
 * 나머지 다섯은 그대로 남는다.
 * - 두 Future 가 현재 요청의 자식이라는 관계는 여전히 코드에 없다.
 * - 하나가 실패해도 나머지는 취소되지 않는다.
 * - 클라이언트가 끊어도 작업은 끝까지 돈다.
 * - 묶음 전체 timeout 을 표현할 자리가 없다.
 * - 톰캣 스레드는 여전히 응답까지 붙잡혀 있다.
 *
 * 그리고 **새로운 부채가 하나 생겼다.** 전파할 항목이 늘어날 때마다
 * [com.example.demo.context.CallContextTaskDecorator] 를 고쳐야 한다.
 * 7단계에서 Spring 7 의 context accessor 가 이 목록을 없앤다.
 */
@Service
class HomeItemServiceV3(
    private val coreBankAdapter: CoreBankAdapter,
    private val homeItemInfoRepository: HomeItemInfoRepository,
    private val openBankingAdapter: OpenBankingAdapter,
    private val userLogRepository: UserLogRepository,
    @Qualifier(HOME_INFO_TASK_EXECUTOR_V3) private val homeInfoTaskExecutorV3: Executor,
    @Qualifier(OPEN_BANKING_TASK_EXECUTOR_V3) private val openBankingTaskExecutorV3: Executor,
) {
    fun getHomeItemsV3(userId: UserId): List<HomeItem> {
        // 코어뱅킹에서 계좌 목록 조회
        val accounts = coreBankAdapter.getAccounts(userId)
        if (accounts.isEmpty()) {
            return emptyList()
        }

        // 개인화 DB 에서 계좌 이름 및 색상 조회 (병렬)
        val accountIds = accounts.map { it.accountId }
        val homeCardInfoFuture = CompletableFuture.supplyAsync(
            { homeItemInfoRepository.getHomeItemInfos(accountIds) },
            homeInfoTaskExecutorV3,
        )

        // 외부에서 오픈뱅킹 잔액 조회 (병렬)
        val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }
        val openBankBalanceFuture = if (openBankAccountIds.isEmpty()) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            CompletableFuture.supplyAsync(
                { openBankingAdapter.getBalances(openBankAccountIds) },
                openBankingTaskExecutorV3,
            )
        }

        // 홈 화면 접속 기록 적재 (비동기) — 응답을 기다리게 하지 않는다.
        userLogRepository.saveEventAsyncV3(userId, UserEvent.GET_HOME)

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
