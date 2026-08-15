package com.example.demo.service

import com.example.demo.adapter.CoreBankAdapter
import com.example.demo.adapter.OpenBankingAdapter
import com.example.demo.adapter.UserLogRepository
import com.example.demo.coroutine.ApplicationCoroutineScope
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
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service

/**
 * 6단계. **[HomeItemServiceV5] 에서 `runBlocking` 을 걷어냈다.**
 *
 * diff 는 세 줄이다.
 *
 * ```
 * - fun getHomeItemsV5(...): List<HomeItem> {
 * + suspend fun getHomeItemsV6(...): List<HomeItem> = coroutineScope {
 *
 * - val accounts = coreBankAdapter.getAccounts(userId)
 * + val accounts = blockingIo { coreBankAdapter.getAccounts(userId) }
 *
 * - val (infos, balances) = runBlocking { ... }
 * + (블록이 그대로 펼쳐진다)
 * ```
 *
 * ## 왜 이게 중요한가
 *
 * 5단계에서 `runBlocking` 은 **코루틴 세계와 blocking 세계의 경계**였다.
 * 그 경계가 서비스 안쪽에 있는 한, 호출 스레드(톰캣)는 계속 막혀 있다.
 *
 * 경계를 위로 밀어 올리면 — 즉 컨트롤러까지 `suspend` 로 만들면 — 경계 자체가 사라지고
 * 톰캣 스레드가 첫 suspension 에서 반납된다.
 *
 * > **코루틴 도입은 "전부 바꾸기" 가 아니라 "경계를 어디에 둘 것인가" 의 문제다.**
 * > 5단계는 경계가 서비스 안, 6단계는 컨트롤러 밖. 코드는 거의 같다.
 *
 * 그래서 실무에서는 5단계 모양으로 먼저 들여놓고, 준비되는 곳부터 경계를 위로 올리면 된다.
 *
 * ## `getAccounts` 도 코루틴 안으로 들어왔다
 *
 * 5단계에서는 코루틴 밖의 평범한 blocking 호출이었다.
 * 톰캣 스레드를 완전히 반납하려면 **체인에 blocking 이 하나도 남으면 안 되므로**
 * 이것도 `blockingIo` 로 감싼다.
 *
 * 4단계에서는 이걸 하지 않았다. 코어뱅킹까지 비동기로 만들려면 executor 를 하나 더 만들고
 * hop 비용을 내야 하는데, 대기가 자리만 옮겨갈 뿐이라 실익이 없었기 때문이다.
 * 여기서는 `blockingIo` 한 줄이면 끝이라 그 비용 자체가 없다.
 * CompletableFuture 에서는 비싸서 포기했던 "마지막 300ms" 를 코루틴은 공짜로 가져간다.
 *
 * ## 남은 문제
 *
 * `Dispatchers.IO` 안에서 MDC 와 CallContext 는 여전히 사라진다. -> 7단계
 * 그리고 `Dispatchers.IO` 자체가 기본 64개로 제한된 플랫폼 스레드 풀이다. -> 10단계
 */
@Service
class HomeItemServiceV6(
    private val coreBankAdapter: CoreBankAdapter,
    private val homeItemInfoRepository: HomeItemInfoRepository,
    private val openBankingAdapter: OpenBankingAdapter,
    private val userLogRepository: UserLogRepository,
    private val applicationCoroutineScope: ApplicationCoroutineScope,
) {

    suspend fun getHomeItemsV6(userId: UserId, failFast: Boolean = false): List<HomeItem> = coroutineScope {
        // 코어뱅킹에서 계좌 목록 조회
        val accounts = blockingIo { coreBankAdapter.getAccounts(userId) }
        if (accounts.isEmpty()) {
            return@coroutineScope emptyList()
        }

        val accountIds = accounts.map { it.accountId }
        val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }

        // 홈 화면 접속 기록 적재 (fire-and-forget)
        // 이 coroutineScope 에 launch 하면 응답이 700ms 늦어진다. 부모를 갈아탄다.
        applicationCoroutineScope.launch(CoroutineName("save-event")) {
            blockingIo { userLogRepository.saveEvent(userId, UserEvent.GET_HOME) }
        }

        // 개인화 DB 에서 계좌 이름 및 색상 조회 (병렬)
        val homeCardInfoDeferred = async(CoroutineName("home-info")) {
            blockingIo { homeItemInfoRepository.getHomeItemInfos(accountIds) }
        }

        // 외부에서 오픈뱅킹 잔액 조회 (병렬)
        val openBankBalanceDeferred = async(CoroutineName("open-banking")) {
            if (openBankAccountIds.isEmpty()) {
                emptyList()
            } else {
                blockingIo { openBankingAdapter.getBalances(openBankAccountIds, failFast) }
            }
        }

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
