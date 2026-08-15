package com.example.demo.adapter

import com.example.demo.domain.OpenBankBalance
import com.example.demo.utils.callContextLabel
import com.example.demo.utils.mockLatency
import com.example.demo.vo.AccountId
import java.math.BigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(OpenBankingAdapter::class.java)

@Component
class OpenBankingAdapter {

    /**
     * 외부 오픈뱅킹 잔액 조회 (mock, 500ms) — 이 데모에서 가장 느린 호출
     *
     * @param failFast 켜면 [FAIL_FAST_MILLIS] 만에 예외를 던진다.
     *   **형제 작업(개인화 DB 조회, 200ms)이 아직 돌고 있는 시점에 실패**시키기 위한 값이다.
     *   이 상태에서 형제가 멈추는지 계속 도는지가 2~4단계와 5단계 이후를 가르는 지점이다.
     *   기본값이 false 라 v1~v3 호출부는 영향을 받지 않는다.
     */
    fun getBalances(accountIds: List<AccountId>, failFast: Boolean = false): List<OpenBankBalance> {
        log.info("[getBalances] start   size={} ctx={}", accountIds.size, callContextLabel())

        if (failFast) {
            mockLatency(log, "getBalances", FAIL_FAST_MILLIS)
            log.warn("[getBalances] 의도된 실패 ctx={}", callContextLabel())
            throw OpenBankingException("오픈뱅킹 응답 실패 (의도된 장애 주입)")
        }

        mockLatency(log, "getBalances", LATENCY_MILLIS)
        log.info("[getBalances] end     size={} ctx={}", accountIds.size, callContextLabel())

        return accountIds.mapIndexed { index, accountId ->
            OpenBankBalance(
                accountId = accountId,
                balance = BigDecimal(BALANCES[index % BALANCES.size]),
            )
        }
    }

    companion object {
        const val LATENCY_MILLIS = 500L

        /** 형제 작업(200ms)이 아직 살아있는 동안 터지도록 일부러 짧게 잡았다. */
        const val FAIL_FAST_MILLIS = 50L

        private val BALANCES = listOf("530000", "78000", "3120000")
    }
}
