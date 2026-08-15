package com.example.demo.service

import com.example.demo.adapter.CoreBankAdapter
import com.example.demo.adapter.OpenBankingAdapter
import com.example.demo.adapter.UserLogRepository
import com.example.demo.config.ContextAwareAsyncConfig.Companion.HOME_INFO_TASK_EXECUTOR_V3
import com.example.demo.config.ContextAwareAsyncConfig.Companion.OPEN_BANKING_TASK_EXECUTOR_V3
import com.example.demo.domain.Account
import com.example.demo.domain.HomeCardInfo
import com.example.demo.domain.HomeItem
import com.example.demo.domain.OpenBankBalance
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
 * 4단계. 서비스가 **값이 아니라 [CompletableFuture] 를 반환**한다.
 *
 * 3단계까지 남아 있던 낭비를 줄인다.
 *
 * > 병렬로 만들어도 톰캣 스레드는 `join()` 으로 결과를 기다리며 응답까지 붙잡혀 있었다.
 *
 * 여기서는 병렬 구간을 콜백 체인(`thenCombine`)으로 걸어두고 즉시 반환한다.
 * `join()` 은 한 번도 부르지 않는다. 톰캣 스레드 점유는 코어뱅킹 조회(300ms)까지로 줄어든다.
 *
 * ## 코어뱅킹 조회는 blocking 으로 남겨둔 이유
 *
 * 이것까지 executor 로 넘기면 점유를 ~0ms 로 만들 수는 있다. 하지만
 * - 대기가 톰캣 스레드에서 executor 스레드로 **자리만 옮겨갈 뿐** thread·ms 총량은 같고
 * - 스레드 hop 비용이 추가되며, 하위 시스템마다 executor 를 만들고 크기를 고민해야 한다
 * - 이득은 과부하 시 "한도를 넘는 대기를 스레드가 아니라 큐 항목으로 표현"하는 것뿐이다
 *
 * 평시 실익이 없는 복잡도라 여기서는 감수하지 않는다.
 * 6단계도 비교 조건을 맞추기 위해 코어뱅킹은 blocking 으로 남겨 320ms 점유를 유지한다.
 *
 * **얻는 것**: 톰캣 스레드 점유가 830ms -> ~300ms (join 대기 제거).
 *
 * **잃는 것**: 코드가 더 이상 위에서 아래로 읽히지 않는다.
 * 1단계의 평범한 6줄이 `thenCompose` / `thenCombine` 중첩으로 바뀌었다.
 * 값이 아니라 "값이 언젠가 담길 상자"를 다루기 시작하면서
 * 조립 로직까지 콜백 안으로 끌려 들어갔다.
 *
 * 그리고 2단계에서 적어둔 문제들은 **하나도 해결되지 않았다.**
 * - 부모-자식 관계 없음
 * - 실패해도 형제 취소 안 됨 (`?failFast=true` 로 확인할 수 있다)
 * - 클라이언트가 끊어도 계속 돎
 * - 묶음 단위 timeout 없음 (DeferredResult 의 timeout 은 **응답**에만 걸린다)
 *
 * 6단계에서 코루틴이 "코어뱅킹 이후 톰캣 스레드 반납"과
 * "위에서 아래로 읽히는 코드"를 동시에 가져간다.
 */
@Service
class HomeItemServiceV4(
    private val coreBankAdapter: CoreBankAdapter,
    private val homeItemInfoRepository: HomeItemInfoRepository,
    private val openBankingAdapter: OpenBankingAdapter,
    private val userLogRepository: UserLogRepository,
    @Qualifier(HOME_INFO_TASK_EXECUTOR_V3) private val homeInfoTaskExecutor: Executor,
    @Qualifier(OPEN_BANKING_TASK_EXECUTOR_V3) private val openBankingTaskExecutor: Executor,
) {

    fun getHomeItemsV4(userId: UserId, failFast: Boolean = false): CompletableFuture<List<HomeItem>> {
        // 코어뱅킹 조회는 blocking 그대로 둔다. 뒤의 두 조회가 이 결과에 의존하므로
        // 어차피 기다려야 하고, executor 로 넘겨봤자 대기가 자리만 옮겨갈 뿐이다.
        val accounts = coreBankAdapter.getAccounts(userId)
        return composeRest(userId, accounts, failFast)
    }

    private fun composeRest(
        userId: UserId,
        accounts: List<Account>,
        failFast: Boolean,
    ): CompletableFuture<List<HomeItem>> {
        if (accounts.isEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
        }

        // 개인화 DB 에서 계좌 이름 및 색상 조회 (병렬)
        val accountIds = accounts.map { it.accountId }
        val homeCardInfoFuture = CompletableFuture.supplyAsync(
            { homeItemInfoRepository.getHomeItemInfos(accountIds) },
            homeInfoTaskExecutor,
        )

        // 외부에서 오픈뱅킹 잔액 조회 (병렬)
        val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }
        val openBankBalanceFuture = if (openBankAccountIds.isEmpty()) {
            CompletableFuture.completedFuture(emptyList())
        } else {
            CompletableFuture.supplyAsync(
                { openBankingAdapter.getBalances(openBankAccountIds, failFast) },
                openBankingTaskExecutor,
            )
        }

        // 홈 화면 접속 기록 적재 (비동기)
        userLogRepository.saveEventAsyncV3(userId, UserEvent.GET_HOME)

        // join() 대신 thenCombine. 여기서 기다리는 스레드가 아무도 없다는 것이 4단계의 전부다.
        //
        // 대신 조립 로직이 콜백 안으로 들어왔다. 1단계에서는 그냥 마지막 return 문이었다.
        // 그리고 openBankBalanceFuture 가 50ms 에 실패해도
        // homeCardInfoFuture 는 200ms 를 채우고 정상 종료한다. 취소되지 않는다.
        return homeCardInfoFuture.thenCombine(openBankBalanceFuture) { homeCardInfos, openBankBalances ->
            assemble(accounts, homeCardInfos, openBankBalances)
        }
    }

    private fun assemble(
        accounts: List<Account>,
        homeCardInfos: List<HomeCardInfo>,
        openBankBalances: List<OpenBankBalance>,
    ): List<HomeItem> {
        val homeItemInfosByAccountId = homeCardInfos.associateBy { it.accountId }
        val openBankBalancesByAccountId = openBankBalances.associateBy { it.accountId }

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
