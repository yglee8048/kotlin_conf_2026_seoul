package com.example.demo.adapter

import com.example.demo.domain.Account
import com.example.demo.vo.UserId
import org.springframework.stereotype.Component

@Component
class CoreBankAdapter {
    fun getAccounts(userId: UserId): List<Account> {
        return emptyList()
    }
}
