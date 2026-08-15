package com.example.demo.service

import com.example.demo.adapter.CoreBankAdapter
import com.example.demo.adapter.OpenBankingAdapter
import com.example.demo.adapter.UserLogRepository
import com.example.demo.coroutine.blockingIo
import com.example.demo.domain.HomeItem
import com.example.demo.domain.UserEvent
import com.example.demo.repository.HomeItemInfoRepository
import com.example.demo.vo.HomeCardColor
import com.example.demo.vo.HomeCardImage
import com.example.demo.vo.UserId
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service

/**
 * 7단계. **[HomeItemServiceV5] 와 로직이 같다.** 컨텍스트 전파를 위해 추가한 코드가 없다.
 *
 * 달라진 건 딱 두 가지고, 둘 다 이 파일 밖에 있다.
 *
 * 1. [com.example.demo.config.ContextPropagationConfig] 가 accessor 를 등록했다.
 * 2. `spring.reactor.context-propagation=auto` 가 켜져 있다.
 *
 * 그 결과 `blockingIo { }` (= `runInterruptible(Dispatchers.IO)`) 안에서도
 * MDC 와 CallContext 가 살아있다. 3단계에서 executor 마다 decorator 를 달아야 했던 일이
 * 코루틴 dispatcher 에 대해서는 **선언만으로** 해결된다.
 *
 * ## 3단계와의 대비가 이 단계의 전부다
 *
 * | | 3단계 | 7단계 |
 * |---|---|---|
 * | 전파 로직 | 직접 구현 (캡처/주입/원복) | Spring + micrometer 가 제공 |
 * | 적용 범위 | 내가 decorator 를 건 executor 만 | 등록된 accessor 를 아는 모든 경계 |
 * | 대상 추가 비용 | decorator 클래스 수정 | accessor 클래스 하나 추가 |
 * | 코루틴 dispatcher | 안 됨 | 됨 |
 *
 * ## 접속 기록 적재만 `@Async` 인 이유
 *
 * 5단계에서는 [com.example.demo.coroutine.ApplicationCoroutineScope] 로 내보냈다.
 * 그런데 그 스코프는 **다른 부모**라서 `PropagationContextElement` 를 물려받지 않는다.
 * 즉 의도적으로 스코프를 벗어난 작업은 컨텍스트도 함께 벗어난다. (v5 로그에서 확인 가능)
 *
 * 여기서는 `ContextPropagatingTaskDecorator` 를 건 executor 로 보내서,
 * **executor 경로 역시 같은 accessor 등록 하나로 해결된다**는 걸 같이 보여준다.
 * 등록해야 할 것은 accessor 하나뿐이고, 경계마다 다른 코드를 쓰지 않는다.
 */
@Service
class HomeItemServiceV7(
    private val coreBankAdapter: CoreBankAdapter,
    private val homeItemInfoRepository: HomeItemInfoRepository,
    private val openBankingAdapter: OpenBankingAdapter,
    private val userLogRepository: UserLogRepository,
) {

    suspend fun getHomeItemsV7(userId: UserId, failFast: Boolean = false): List<HomeItem> = coroutineScope {
        // 코어뱅킹에서 계좌 목록 조회
        val accounts = blockingIo { coreBankAdapter.getAccounts(userId) }
        if (accounts.isEmpty()) {
            return@coroutineScope emptyList()
        }

        // 개인화 DB 에서 계좌 이름 및 색상 조회 (병렬)
        val accountIds = accounts.map { it.accountId }
        val homeCardInfoDeferred = async(CoroutineName("home-info")) {
            blockingIo { homeItemInfoRepository.getHomeItemInfos(accountIds) }
        }

        // 외부에서 오픈뱅킹 잔액 조회 (병렬)
        val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }
        val openBankBalanceDeferred = async(CoroutineName("open-banking")) {
            if (openBankAccountIds.isEmpty()) {
                emptyList()
            } else {
                blockingIo { openBankingAdapter.getBalances(openBankAccountIds, failFast) }
            }
        }

        // 홈 화면 접속 기록 적재 (fire-and-forget)
        // 지금 이 스레드에 컨텍스트가 복원되어 있으므로, 제출 시점 스냅샷에 그대로 담긴다.
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
