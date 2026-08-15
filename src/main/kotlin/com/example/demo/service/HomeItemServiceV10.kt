package com.example.demo.service

import com.example.demo.adapter.CoreBankAdapter
import com.example.demo.adapter.OpenBankingAdapter
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
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * 10단계. **[HomeItemServiceV7] 에서 dispatcher 만 바꿨다.**
 *
 * ```
 * blockingIo { ... }                        // 7단계: Dispatchers.IO (플랫폼 스레드 64개)
 * blockingIo(virtualThreadDispatcher) { ... } // 10단계: 가상 스레드
 * ```
 *
 * 병렬 조회 로그에서 `P[DefaultDispatcher-worker-1]` 이 `V[vt-dispatch-3]` 으로 바뀐다.
 * 코어뱅킹 호출은 이전 단계와 같이 톰캣 스레드에서 실행한다.
 * 응답 시간은 변하지 않는다. **동시성이 낮을 때는 아무 이득이 없다는 걸 먼저 인정하고 시작한다.**
 * 차이는 동시 요청이 수백 개로 갈 때 나온다.
 *
 * ## 컨텍스트 전파는 그대로 따라온다
 *
 * dispatcher 를 바꿔도 7단계에서 등록한 accessor 가 그대로 동작한다.
 * `PropagationContextElement` 는 dispatcher 가 아니라 **코루틴 재개 시점**에 걸리기 때문이다.
 * 3단계 방식(executor 마다 decorator)이었다면 새 executor 에 또 달아야 했다.
 *
 * ## 여전히 `runInterruptible` 이 필요하다
 *
 * 가상 스레드라고 취소가 저절로 되지는 않는다. 취소 -> 인터럽트 -> blocking 코드가 중단,
 * 이 사슬은 똑같이 필요하다. **가상 스레드는 스레드를 싸게 만들 뿐, 수명 관리를 대신해주지 않는다.**
 * 이 발표의 결론이 여기서 한 번 더 나온다.
 */
@Service
class HomeItemServiceV10(
    private val coreBankAdapter: CoreBankAdapter,
    private val homeItemInfoRepository: HomeItemInfoRepository,
    private val openBankingAdapter: OpenBankingAdapter,
    private val userLogRepository: UserLogRepository,
    @Qualifier(VIRTUAL_THREAD_DISPATCHER) private val virtualThreadDispatcher: ExecutorCoroutineDispatcher,
) {

    suspend fun getHomeItemsV10(userId: UserId, failFast: Boolean = false): List<HomeItem> = coroutineScope {
        // 코어뱅킹 조회는 톰캣 가상 스레드에서 blocking 으로 실행한다.
        val accounts = coreBankAdapter.getAccounts(userId)
        if (accounts.isEmpty()) {
            return@coroutineScope emptyList()
        }

        // 개인화 DB 에서 계좌 이름 및 색상 조회 (병렬)
        val accountIds = accounts.map { it.accountId }
        val homeCardInfoDeferred = async(CoroutineName("home-info")) {
            blockingIo(virtualThreadDispatcher) { homeItemInfoRepository.getHomeItemInfos(accountIds) }
        }

        // 외부에서 오픈뱅킹 잔액 조회 (병렬)
        val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }
        val openBankBalanceDeferred = async(CoroutineName("open-banking")) {
            if (openBankAccountIds.isEmpty()) {
                emptyList()
            } else {
                blockingIo(virtualThreadDispatcher) {
                    openBankingAdapter.getBalances(openBankAccountIds, failFast)
                }
            }
        }

        // 홈 화면 접속 기록 적재 (fire-and-forget)
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
