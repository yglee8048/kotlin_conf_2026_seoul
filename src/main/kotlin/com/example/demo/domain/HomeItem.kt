package com.example.demo.domain

import com.example.demo.vo.AccountId
import com.example.demo.vo.AccountType
import com.example.demo.vo.HomeCardColor
import com.example.demo.vo.HomeCardImage
import java.math.BigDecimal

data class HomeItem(
    val accountId: AccountId,
    val accountType: AccountType,
    val balance: BigDecimal?,
    val alias: String,
    val color: HomeCardColor,
    val image: HomeCardImage,
)
