package com.example.demo.adapter

import com.example.demo.domain.Account
import com.example.demo.vo.AccountId
import com.example.demo.vo.AccountType
import com.example.demo.vo.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

private val log = LoggerFactory.getLogger(CoreBankAdapter::class.java)

@Component
class CoreBankAdapter {

    /** 코어뱅킹 계좌 목록 조회 (mock, 300ms) */
    fun getAccounts(userId: UserId): List<Account> {
        log.info("[getAccounts] start   userId={} thread={}", userId.value, Thread.currentThread())
        Thread.sleep(LATENCY_MILLIS)
        log.info("[getAccounts] end     userId={} thread={}", userId.value, Thread.currentThread())

        return listOf(
            Account(
                accountId = AccountId(accountNo = "110-1234-5678", bankCode = "088"),
                accountType = AccountType.DEPOSIT,
                balance = BigDecimal("1250000"),
            ),
            Account(
                accountId = AccountId(accountNo = "333-9876-5432", bankCode = "004"),
                accountType = AccountType.OPEN_BANK,
                balance = null,
            ),
            Account(
                accountId = AccountId(accountNo = "777-1111-2222", bankCode = "020"),
                accountType = AccountType.OPEN_BANK,
                balance = null,
            ),
        )
    }

    companion object {
        const val LATENCY_MILLIS = 300L
    }
}
