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

    /** 외부 오픈뱅킹 잔액 조회 (mock, 500ms) — 이 데모에서 가장 느린 호출 */
    fun getBalances(accountIds: List<AccountId>): List<OpenBankBalance> {
        log.info("[getBalances] start   size={} ctx={}", accountIds.size, callContextLabel())
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

        private val BALANCES = listOf("530000", "78000", "3120000")
    }
}
