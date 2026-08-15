package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemServiceV10
import com.example.demo.vo.UserId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 10단계 엔드포인트. 7단계와 동일하고 dispatcher 만 가상 스레드다. */
@RestController
@RequestMapping("/api/v10/home")
class HomeItemV10Controller(
    private val homeItemServiceV10: HomeItemServiceV10,
) {

    @GetMapping("/items")
    suspend fun getHomeItems(
        userId: UserId,
        @RequestParam(defaultValue = "false") failFast: Boolean,
    ): List<HomeItem> {
        return homeItemServiceV10.getHomeItemsV10(userId, failFast)
    }
}
