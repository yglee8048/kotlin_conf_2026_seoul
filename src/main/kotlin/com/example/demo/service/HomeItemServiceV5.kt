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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service

/**
 * 5단계. 같은 일을 코루틴으로. **단, 병렬이 필요한 구간에만.**
 *
 * ## 이 파일의 모양이 5단계의 메시지다
 *
 * `getHomeItemsV5` 는 `suspend` 가 아니다. **평범한 blocking 메서드**다.
 * 컨트롤러도 1단계와 똑같이 생겼고, 반환 타입도 `List<HomeItem>` 그대로다.
 *
 * 코루틴은 딱 한 군데, `runBlocking { }` 블록 안에만 있다.
 *
 * ```
 * val accounts = coreBankAdapter.getAccounts(userId)   // 그냥 blocking
 *
 * val (infos, balances) = runBlocking {                 // 여기만 코루틴
 *     val a = async { ... }
 *     val b = async { ... }
 *     a.await() to b.await()
 * }
 *
 * return accounts.map { ... }                           // 그냥 blocking
 * ```
 *
 * 실무에서 기존 blocking MVC 코드베이스에 코루틴을 처음 들일 때 딱 이 모양이 된다.
 * **전체를 바꾸지 않아도, 병렬이 필요한 그 블록만 감싸면 오늘 도입할 수 있다.**
 * 호출자도, 컨트롤러도, 시그니처도 그대로다.
 *
 * WebFlux 처럼 전체 스택을 갈아엎어야 하는 전환이 아니라는 것이
 * 이 발표의 가장 실용적인 답이다.
 *
 * ## 비교 상대는 3단계다 (4단계가 아니라)
 *
 * 5단계는 톰캣 스레드를 828ms 붙잡으므로, 스레드를 반납하는 4단계와 붙이면 불공정하다.
 * 같은 스레드 프로파일인 3단계와 붙이면 남는 차이는 코드 구조뿐이다:
 * executor 배선과 join 이 async/await 로, 형제 미취소가 취소로, 누수 가능이 문법적 불가능으로.
 * 4단계와의 비교는 6단계(둘 다 스레드 반납)에서 한다.
 *
 * ## `runBlocking` 은 그 자체로 스코프다
 *
 * 별도로 `coroutineScope { }` 를 겹쳐 쓸 필요가 없다.
 * `runBlocking` 이 만드는 코루틴은 일반 Job 을 가지므로,
 * 자식 하나가 실패하면 **형제가 취소되고** 블록을 빠져나올 때 자식이 하나도 안 남아 있음이 보장된다.
 *
 * | 2단계에서 답할 수 없던 질문 | 여기서는 |
 * |---|---|
 * | 두 작업이 한 묶음인가? | `runBlocking` 블록 안에 있으면 그렇다 |
 * | 하나가 실패하면 나머지는? | 형제가 **취소된다** (`?failFast=true` 로 확인) |
 * | 이 묶음의 timeout 은? | `withTimeout { }` 으로 블록 전체에 건다 (11단계) |
 * | 언제 다 끝났다고 볼 수 있나? | 블록을 빠져나오면 자식이 안 남아 있음이 보장된다 |
 *
 * 마지막 줄이 특히 중요하다. **누수가 문법적으로 불가능하다.**
 * CompletableFuture 에서는 `join()` 을 빼먹으면 그냥 새어나간다.
 *
 * ## 아직 톰캣 스레드는 붙잡혀 있다
 *
 * `runBlocking` 이 호출 스레드를 막기 때문이다. 게다가 `getAccounts` 는 아예 코루틴 밖이라
 * 그 300ms 동안도 톰캣 스레드가 그대로 blocking 이다.
 *
 * 즉 5단계의 성과는 **코드 구조**지 스레드 효율이 아니다.
 * 6단계에서 `runBlocking` 을 걷어내고 `suspend` 로 넓히면 그것까지 해결된다.
 * **"간결함"과 "스레드 반납"은 서로 다른 축**이고, 코루틴은 둘 다 가져간다.
 *
 * ## 남은 문제
 *
 * `blockingIo` (= `Dispatchers.IO`) 안에서는 MDC 와 CallContext 가 사라진다.
 * 3단계에서 executor 에 걸어둔 decorator 는 코루틴 dispatcher 에는 걸려 있지 않다.
 * -> 7단계
 */
@Service
class HomeItemServiceV5(
    private val coreBankAdapter: CoreBankAdapter,
    private val homeItemInfoRepository: HomeItemInfoRepository,
    private val openBankingAdapter: OpenBankingAdapter,
    private val userLogRepository: UserLogRepository,
    private val applicationCoroutineScope: ApplicationCoroutineScope,
) {

    fun getHomeItemsV5(userId: UserId, failFast: Boolean = false): List<HomeItem> {
        // 코어뱅킹에서 계좌 목록 조회 — 1단계와 똑같은 평범한 blocking 호출이다.
        // 뒤의 두 조회가 이 결과에 의존하므로 병렬화 대상이 아니고, 코루틴으로 감쌀 이유도 없다.
        val accounts = coreBankAdapter.getAccounts(userId)
        if (accounts.isEmpty()) {
            return emptyList()
        }

        val accountIds = accounts.map { it.accountId }
        val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }

        // 홈 화면 접속 기록 적재 (fire-and-forget)
        //
        // `launch` 는 suspend 함수가 아니라서 **평범한 blocking 코드에서도 그냥 호출된다.**
        // 아래 runBlocking 안에 넣으면 응답이 700ms 늦어진다.
        // 구조적 동시성은 블록을 빠져나가기 전에 모든 자식을 기다리기 때문이다.
        //
        // 응답보다 오래 살아야 하는 작업은 부모를 명시적으로 갈아타야 한다.
        // "실수로 새어나가는 것"과 "의도적으로 내보내는 것"이 코드에서 구분된다는 뜻이기도 하다.
        applicationCoroutineScope.launch(CoroutineName("save-event")) {
            blockingIo { userLogRepository.saveEvent(userId, UserEvent.GET_HOME) }
        }

        // 병렬이 필요한 구간만 코루틴으로 들어갔다 나온다.
        // runBlocking 이 톰캣 스레드를 막는 대신, 코루틴의 경계가 이 블록으로 명확해진다.
        val (homeCardInfos, openBankBalances) = runBlocking {
            // 개인화 DB 에서 계좌 이름 및 색상 조회
            val homeCardInfoDeferred = async(CoroutineName("home-info")) {
                blockingIo { homeItemInfoRepository.getHomeItemInfos(accountIds) }
            }

            // 외부에서 오픈뱅킹 잔액 조회
            val openBankBalanceDeferred = async(CoroutineName("open-banking")) {
                if (openBankAccountIds.isEmpty()) {
                    emptyList()
                } else {
                    blockingIo { openBankingAdapter.getBalances(openBankAccountIds, failFast) }
                }
            }

            homeCardInfoDeferred.await() to openBankBalanceDeferred.await()
        }

        // 응답 조립 — 1단계와 같은 자리로 돌아왔다.
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
