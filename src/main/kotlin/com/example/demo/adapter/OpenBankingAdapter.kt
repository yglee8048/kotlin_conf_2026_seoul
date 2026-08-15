package com.example.demo.adapter

import com.example.demo.domain.OpenBankBalance
import com.example.demo.vo.AccountId
import org.springframework.stereotype.Component

@Component
class OpenBankingAdapter {
    fun getBalances(accountIds: List<AccountId>): List<OpenBankBalance> {
        return emptyList()
    }
}
