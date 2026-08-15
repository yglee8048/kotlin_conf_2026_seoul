package com.example.demo.domain

import com.example.demo.vo.AccountId
import com.example.demo.vo.AccountType
import java.math.BigDecimal

data class Account(
    val accountId: AccountId,
    val accountType: AccountType,
    val balance: BigDecimal?,
) {
    fun isOpenBank(): Boolean {
        return accountType == AccountType.OPEN_BANK
    }
}
