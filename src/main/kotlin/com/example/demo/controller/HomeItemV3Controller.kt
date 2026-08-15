package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemServiceV3
import com.example.demo.vo.UserId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 3단계 엔드포인트. 2단계와 응답도 응답 시간도 같다.
 * 달라지는 것은 worker 스레드의 **로그**뿐이다.
 */
@RestController
@RequestMapping("/api/v3/home")
class HomeItemV3Controller(
    private val homeItemServiceV3: HomeItemServiceV3,
) {

    @GetMapping("/items")
    fun getHomeItems(userId: UserId): List<HomeItem> {
        return homeItemServiceV3.getHomeItemsV3(userId)
    }
}
