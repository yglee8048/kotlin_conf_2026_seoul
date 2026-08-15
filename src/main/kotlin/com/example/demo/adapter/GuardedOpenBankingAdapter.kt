package com.example.demo.adapter

import com.example.demo.domain.OpenBankBalance
import com.example.demo.utils.callContextLabel
import com.example.demo.vo.AccountId
import org.slf4j.LoggerFactory
import org.springframework.resilience.annotation.ConcurrencyLimit
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(GuardedOpenBankingAdapter::class.java)

/**
 * 11단계. [OpenBankingAdapter] 에 동시 호출 상한을 씌운다.
 *
 * ## 2단계에서 남긴 숙제
 *
 * "오픈뱅킹 상대 시스템에 동시 호출 30개를 넘기지 않겠다" 를 스레드 풀로 표현하려 했을 때
 * 선택지가 둘뿐이었고 둘 다 문제가 있었다.
 *
 * | 정책 | 큐가 찼을 때 | 문제 |
 * |---|---|---|
 * | `CallerRunsPolicy` | 호출 스레드가 대신 실행 | 상한이 **새어나간다** (30 + 톰캣 스레드 수) |
 * | `AbortPolicy` | 예외로 거절 | 상한은 지켜지지만 **버린다** |
 *
 * 그리고 10단계에서 가상 스레드 dispatcher 로 바꾸는 순간 **상한 자체가 사라졌다.**
 * 스레드 풀이 겸하고 있던 '동시성 제한' 역할이 없어졌기 때문이다.
 *
 * ## Spring 7 의 답
 *
 * [ConcurrencyLimit] 은 동시성 제한을 **실행 자원(스레드 풀)에서 분리한다.**
 * 세마포어 기반이라 어떤 스레드에서 호출하든, 가상 스레드든 플랫폼 스레드든 상한이 지켜진다.
 *
 * - [ConcurrencyLimit.ThrottlePolicy.BLOCK] (기본): 자리가 날 때까지 **기다린다**. 안 버린다.
 * - [ConcurrencyLimit.ThrottlePolicy.REJECT]: `InvocationRejectedException` 으로 즉시 거절한다.
 *
 * 즉 2단계에서 "새거나 버리거나 둘 중 하나" 였던 것이 "기다리거나 거절하거나" 가 됐고,
 * **기다리는 쪽이 톰캣 스레드를 태우지 않는다.** 가상 스레드 위에서 기다리는 건 값싸기 때문이다.
 *
 * > 이게 8단계 이후 스레드 풀이 사라져도 안정성이 유지되는 방식이다.
 * > 값싼 실행(가상 스레드) + 명시적 상한(@ConcurrencyLimit) 의 조합.
 *
 * ## 주의
 *
 * 프록시 기반이다. 그래서 [OpenBankingAdapter] 에 직접 붙이지 않고 별도 빈으로 감쌌다.
 * (직접 붙였다면 v1~v10 이 전부 영향을 받아 앞 단계 시연이 달라진다)
 *
 * 상한을 3으로 잡은 건 시연 때문이다. curl 을 10개 동시에 던지면 바로 대기가 보인다.
 */
@Component
class GuardedOpenBankingAdapter(
    private val openBankingAdapter: OpenBankingAdapter,
) {

    @ConcurrencyLimit(CONCURRENCY_LIMIT)
    fun getBalances(accountIds: List<AccountId>, failFast: Boolean = false): List<OpenBankBalance> {
        log.info("[guarded] 상한({}) 통과 ctx={}", CONCURRENCY_LIMIT, callContextLabel())
        return openBankingAdapter.getBalances(accountIds, failFast)
    }

    companion object {
        /** 시연에서 바로 대기가 보이도록 일부러 작게 잡았다. */
        const val CONCURRENCY_LIMIT = 3
    }
}
