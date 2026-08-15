package com.example.demo.domain

import com.example.demo.vo.AccountId
import java.math.BigDecimal

data class OpenBankBalance(
    val accountId: AccountId,
    val balance: BigDecimal?,
)
