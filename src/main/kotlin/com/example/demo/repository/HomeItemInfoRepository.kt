package com.example.demo.repository

import com.example.demo.domain.HomeCardInfo
import com.example.demo.vo.AccountId
import org.springframework.stereotype.Component

@Component
class HomeItemInfoRepository {
    fun getHomeItemInfos(accountIds: List<AccountId>): List<HomeCardInfo> {
        return emptyList()
    }
}
