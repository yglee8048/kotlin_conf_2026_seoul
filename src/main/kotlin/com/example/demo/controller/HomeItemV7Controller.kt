package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemServiceV7
import com.example.demo.vo.UserId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 7단계 엔드포인트. 6단계와 시그니처가 완전히 같다.
 *
 * 이 단계의 변화는 전부 설정에 있고 컨트롤러에는 없다.
 * 시연은 `POST /api/demo/context-accessors?enabled=false|true` 로 accessor 등록을
 * 껐다 켜면서 **같은 엔드포인트를 두 번 호출**하는 것으로 한다.
 */
@RestController
@RequestMapping("/api/v7/home")
class HomeItemV7Controller(
    private val homeItemServiceV7: HomeItemServiceV7,
) {

    @GetMapping("/items")
    suspend fun getHomeItems(
        userId: UserId,
        @RequestParam(defaultValue = "false") failFast: Boolean,
    ): List<HomeItem> {
        return homeItemServiceV7.getHomeItemsV7(userId, failFast)
    }
}
