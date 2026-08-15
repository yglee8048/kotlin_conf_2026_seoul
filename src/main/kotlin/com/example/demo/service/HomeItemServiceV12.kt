package com.example.demo.service

import com.example.demo.adapter.CoreBankAdapter
import com.example.demo.adapter.OpenBankingAdapter
import com.example.demo.adapter.UserLogRepository
import com.example.demo.domain.HomeCardInfo
import com.example.demo.domain.HomeItem
import com.example.demo.domain.OpenBankBalance
import com.example.demo.domain.UserEvent
import com.example.demo.repository.HomeItemInfoRepository
import com.example.demo.vo.HomeCardColor
import com.example.demo.vo.HomeCardImage
import com.example.demo.vo.UserId
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.StructuredTaskScope

/**
 * 12단계 (번외). 코루틴 없이, **가상 스레드만으로** 병렬·비동기·구조적 동시성을 구현한다.
 *
 * JDK 25 의 `StructuredTaskScope` 는 아직 preview 다. (JEP 505)
 * 빌드와 실행에 `--enable-preview` 가 필요하다. (build.gradle.kts 참고)
 *
 * ## 코루틴과 거의 1:1로 대응된다
 *
 * ```kotlin
 * // 5단계 (코루틴)
 * coroutineScope {
 *     val a = async { infoRepository.get(ids) }
 *     val b = async { openBanking.get(ids) }
 *     assemble(a.await(), b.await())
 * }
 *
 * // 12단계 (StructuredTaskScope)
 * StructuredTaskScope.open(...).use { scope ->
 *     val a = scope.fork(Callable { infoRepository.get(ids) })
 *     val b = scope.fork(Callable { openBanking.get(ids) })
 *     scope.join()
 *     assemble(a.get(), b.get())
 * }
 * ```
 *
 * 얻는 것도 같다. 부모-자식 관계, 형제 취소, 묶음 timeout, 누수 불가능.
 * **구조적 동시성은 코루틴만의 것이 아니다.** 이걸 인정하고 시작하는 것이 이 단계의 목적이다.
 *
 * ## 그래도 남는 차이
 *
 * | | 코루틴 | StructuredTaskScope |
 * |---|---|---|
 * | 상태 | 정식 (kotlinx.coroutines) | **preview** (JDK 25 기준 5차) |
 * | 취소 전달 | 협조적 취소 + 인터럽트 | 인터럽트 |
 * | blocking 래핑 | `runInterruptible` 필요 | 불필요 (원래 blocking 세계) |
 * | 컨텍스트 전파 | accessor 자동 (7단계) | `ScopedValue` 를 별도로 써야 함 |
 * | 컨트롤러 통합 | `suspend` 반환 타입 그대로 | 톰캣 스레드를 **붙잡는다** |
 * | Flow / Channel 같은 스트림 | 있음 | 없음 |
 *
 * 마지막 두 줄이 실전에서 크다.
 *
 * 특히 **이 서비스는 톰캣 스레드를 반납하지 않는다.** `scope.join()` 이 호출 스레드를 막기 때문이다.
 * vt 프로파일에서는 그 스레드가 가상 스레드라 값싸므로 문제되지 않는다.
 * 뒤집어 말하면 **이 방식은 가상 스레드가 켜져 있다는 전제 위에서만 성립한다.**
 * 6단계의 suspend 컨트롤러는 그런 전제 없이도 스레드를 반납했다.
 *
 * > 정리하면: 가상 스레드는 코루틴이 필요했던 이유 중 '값싼 실행' 을 없앴고,
 * > `StructuredTaskScope` 는 '구조화' 마저 자바 쪽으로 가져왔다.
 * > 그럼에도 preview 상태, 스트림 처리, 프레임워크 통합에서 코루틴이 앞선다.
 */
@Service
class HomeItemServiceV12(
    private val coreBankAdapter: CoreBankAdapter,
    private val homeItemInfoRepository: HomeItemInfoRepository,
    private val openBankingAdapter: OpenBankingAdapter,
    private val userLogRepository: UserLogRepository,
) {

    fun getHomeItemsV12(
        userId: UserId,
        failFast: Boolean = false,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): List<HomeItem> {
        // 코어뱅킹에서 계좌 목록 조회
        val accounts = coreBankAdapter.getAccounts(userId)
        if (accounts.isEmpty()) {
            return emptyList()
        }

        val accountIds = accounts.map { it.accountId }
        val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }

        // 홈 화면 접속 기록 적재 (fire-and-forget)
        //
        // **스코프 밖에서 호출해야 한다.** scope.close() 는 모든 자식이 끝날 때까지 기다리므로
        // 안에 넣으면 응답이 700ms 늦어진다. 코루틴에서 부모를 갈아탔던 것과 같은 이유다.
        // 구조적 동시성을 쓰면 "새어나가는 작업"을 만들려면 반드시 명시해야 한다.
        userLogRepository.saveEventAsyncV7(userId, UserEvent.GET_HOME)

        // Joiner.awaitAllSuccessfulOrThrow(): 하나라도 실패하면 나머지를 취소하고 예외를 올린다.
        // withTimeout: 묶음 전체에 거는 timeout. 코루틴의 withTimeout 과 같은 자리다.
        //
        // 참고: Kotlin 에서는 fork(Callable) 과 fork(Runnable) 오버로드가 모호해서
        // 람다를 그냥 넘기면 Runnable 쪽이 선택되고 결과가 null 이 된다. Callable 로 명시해야 한다.
        StructuredTaskScope.open<Any, Void>(
            StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(),
            { config -> config.withName("home-v12").withTimeout(Duration.ofMillis(timeoutMillis)) },
        ).use { scope ->
            // 개인화 DB 에서 계좌 이름 및 색상 조회 (병렬)
            val homeCardInfoTask = scope.fork(
                Callable { homeItemInfoRepository.getHomeItemInfos(accountIds) },
            )

            // 외부에서 오픈뱅킹 잔액 조회 (병렬)
            val openBankBalanceTask = scope.fork(
                Callable {
                    if (openBankAccountIds.isEmpty()) {
                        emptyList()
                    } else {
                        openBankingAdapter.getBalances(openBankAccountIds, failFast)
                    }
                },
            )

            scope.join()

            @Suppress("UNCHECKED_CAST")
            return assemble(
                accounts = accounts,
                homeCardInfos = homeCardInfoTask.get() as List<HomeCardInfo>,
                openBankBalances = openBankBalanceTask.get() as List<OpenBankBalance>,
            )
        }
    }

    private fun assemble(
        accounts: List<com.example.demo.domain.Account>,
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

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L
    }
}
