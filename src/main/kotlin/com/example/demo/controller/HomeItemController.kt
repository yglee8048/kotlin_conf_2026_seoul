package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemService
import com.example.demo.vo.UserId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/home")
class HomeItemController(
    private val homeItemService: HomeItemService,
) {

    @GetMapping("/items")
    fun getHomeItems(userId: UserId): List<HomeItem> {
        return homeItemService.getHomeItems(userId)
    }
}
