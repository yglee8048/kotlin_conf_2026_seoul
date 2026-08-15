package com.example.demo.service

import com.example.demo.adapter.CoreBankAdapter
import com.example.demo.adapter.GuardedOpenBankingAdapter
import com.example.demo.adapter.UserLogRepository
import com.example.demo.config.VirtualThreadConfig.Companion.VIRTUAL_THREAD_DISPATCHER
import com.example.demo.coroutine.blockingIo
import com.example.demo.domain.HomeItem
import com.example.demo.domain.UserEvent
import com.example.demo.repository.HomeItemInfoRepository
import com.example.demo.vo.HomeCardColor
import com.example.demo.vo.HomeCardImage
import com.example.demo.vo.UserId
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * 11단계. 가상 스레드 환경에서 안정성을 되찾는다.
 *
 * 10단계에서 두 가지가 사라졌다는 걸 확인했다.
 *
 * 1. 스레드 풀이 겸하던 **동시 호출 상한** -> `@ConcurrencyLimit` ([GuardedOpenBankingAdapter])
 * 2. 큐 대기가 사라지면서 드러난 **무한정 기다림** -> `withTimeout`
 *
 * ## `withTimeout` 이 4단계의 timeout 과 다른 점
 *
 * 4단계에서도 `DeferredResult(3000)` 으로 timeout 을 걸 수 있었다. 하지만 그건
 * **응답에만** 걸리는 timeout 이었다. 시간이 지나 에러를 내려보내도 뒤의
 * CompletableFuture 들은 계속 돌았다. 요청은 실패했는데 부하는 그대로 남는다.
 *
 * 여기서는 `withTimeout` 이 스코프 전체를 취소하고, 취소가 자식으로 전파되고,
 * `runInterruptible` 이 실제 blocking 호출까지 인터럽트한다.
 *
 * > **작업 묶음 전체에 timeout 을 걸 자리가 없다** — 2단계에서 적어둔 네 번째 문제가
 * > 여기서 닫힌다. 그리고 그게 가능한 이유는 5단계에서 확보한 구조적 동시성 때문이다.
 *
 * 로그로 확인할 수 있다. `?timeoutMillis=400` 으로 부르면
 * `[getBalances] 취소됨(인터럽트)` 이 찍히고 `end` 는 찍히지 않는다.
 *
 * ## 조합이 핵심이다
 *
 * ```
 * 가상 스레드   : 실행을 값싸게 만든다        (8~10단계)
 * 구조적 동시성 : 수명과 취소를 표현한다      (5단계)
 * @ConcurrencyLimit : 하위 시스템을 보호한다  (11단계)
 * ```
 *
 * 세 개는 서로를 대체하지 않는다. 가상 스레드가 코루틴을 대체하지 않는다는
 * 이 발표의 결론이 여기서 코드로 드러난다.
 */
@Service
class HomeItemServiceV11(
    private val coreBankAdapter: CoreBankAdapter,
    private val homeItemInfoRepository: HomeItemInfoRepository,
    private val guardedOpenBankingAdapter: GuardedOpenBankingAdapter,
    private val userLogRepository: UserLogRepository,
    @Qualifier(VIRTUAL_THREAD_DISPATCHER) private val virtualThreadDispatcher: ExecutorCoroutineDispatcher,
) {

    suspend fun getHomeItemsV11(
        userId: UserId,
        failFast: Boolean = false,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): List<HomeItem> = withTimeout(timeoutMillis) {
        coroutineScope {
            // 코어뱅킹에서 계좌 목록 조회
            val accounts = blockingIo(virtualThreadDispatcher) { coreBankAdapter.getAccounts(userId) }
            if (accounts.isEmpty()) {
                return@coroutineScope emptyList()
            }

            // 개인화 DB 에서 계좌 이름 및 색상 조회 (병렬)
            val accountIds = accounts.map { it.accountId }
            val homeCardInfoDeferred = async(CoroutineName("home-info")) {
                blockingIo(virtualThreadDispatcher) { homeItemInfoRepository.getHomeItemInfos(accountIds) }
            }

            // 외부에서 오픈뱅킹 잔액 조회 (병렬) — 동시 호출 상한이 걸린 어댑터를 쓴다.
            //
            // 상한에 걸리면 여기서 대기한다. 대기하는 것은 가상 스레드라 값싸고,
            // 톰캣 스레드는 이미 6단계에서 반납했으므로 영향이 없다.
            // 2단계에서 CallerRunsPolicy 가 톰캣 스레드를 태우던 것과 대비된다.
            val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }
            val openBankBalanceDeferred = async(CoroutineName("open-banking")) {
                if (openBankAccountIds.isEmpty()) {
                    emptyList()
                } else {
                    blockingIo(virtualThreadDispatcher) {
                        guardedOpenBankingAdapter.getBalances(openBankAccountIds, failFast)
                    }
                }
            }

            // 홈 화면 접속 기록 적재 (fire-and-forget)
            //
            // 이 작업은 withTimeout 의 영향을 받지 않는다. 스코프 밖의 executor 로 나가기 때문이다.
            // 의도한 동작이다. 응답이 timeout 났다고 접속 기록까지 버릴 이유는 없다.
            userLogRepository.saveEventAsyncV7(userId, UserEvent.GET_HOME)

            val homeItemInfosByAccountId = homeCardInfoDeferred.await().associateBy { it.accountId }
            val openBankBalancesByAccountId = openBankBalanceDeferred.await().associateBy { it.accountId }

            // 응답 조립
            accounts.map {
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

    companion object {
        /** 정상 경로가 800ms 라 여유를 두고 잡았다. 시연에서는 400ms 로 낮춰 부른다. */
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L
    }
}
