package com.example.demo.service

import com.example.demo.adapter.CoreBankAdapter
import com.example.demo.adapter.OpenBankingAdapter
import com.example.demo.adapter.UserLogRepository
import com.example.demo.domain.HomeItem
import com.example.demo.domain.UserEvent
import com.example.demo.repository.HomeItemInfoRepository
import com.example.demo.vo.HomeCardColor
import com.example.demo.vo.HomeCardImage
import com.example.demo.vo.UserId
import org.springframework.stereotype.Service

@Service
class HomeItemService(
    val coreBankAdapter: CoreBankAdapter,
    val homeItemInfoRepository: HomeItemInfoRepository,
    val openBankingAdapter: OpenBankingAdapter,
    val userLogRepository: UserLogRepository,
) {
    fun getHomeItems(userId: UserId): List<HomeItem> {
        // 코어뱅킹에서 계좌 목록 조회
        val accounts = coreBankAdapter.getAccounts(userId)
        if (accounts.isEmpty()) {
            return emptyList()
        }

        // 개인화 DB 에서 계좌 이름 및 색상 조회
        val accountIds = accounts.map { it.accountId }
        val homeItemInfosByAccountId = homeItemInfoRepository.getHomeItemInfos(accountIds)
            .associateBy { it.accountId }

        // 외부에서 오픈뱅킹 잔액 조회
        val openBankAccountIds = accounts.filter { it.isOpenBank() }.map { it.accountId }
        val openBankBalancesByAccountId = if (openBankAccountIds.isNotEmpty()) {
            openBankingAdapter.getBalances(openBankAccountIds)
                .associateBy { it.accountId }
        } else {
            emptyMap()
        }

        // 홈 화면 접속 기록 적재
        userLogRepository.saveEvent(userId, UserEvent.GET_HOME)

        // 응답 조립
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
}
