package com.example.demo.domain

import com.example.demo.vo.AccountId
import com.example.demo.vo.HomeCardColor
import com.example.demo.vo.HomeCardImage

data class HomeCardInfo(
    val accountId: AccountId,
    val alias: String,
    val color: HomeCardColor,
    val image: HomeCardImage,
)
