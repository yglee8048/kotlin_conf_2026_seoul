package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemServiceV2
import com.example.demo.vo.UserId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 2단계 엔드포인트. 톰캣 스레드는 1단계와 똑같이 응답까지 붙잡혀 있고,
 * 달라진 것은 서비스 내부에서 조회를 병렬로 돌린다는 점뿐이다.
 */
@RestController
@RequestMapping("/api/v2/home")
class HomeItemV2Controller(
    private val homeItemServiceV2: HomeItemServiceV2,
) {

    @GetMapping("/items")
    fun getHomeItems(userId: UserId): List<HomeItem> {
        return homeItemServiceV2.getHomeItemsV2(userId)
    }
}
