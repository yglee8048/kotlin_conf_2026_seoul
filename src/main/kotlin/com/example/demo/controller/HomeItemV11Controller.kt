package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemServiceV11
import com.example.demo.vo.UserId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 11단계 엔드포인트.
 *
 * ```bash
 * # 정상
 * curl ".../api/v11/home/items?value=user-1"
 *
 * # 묶음 timeout — getBalances 가 인터럽트되는 것이 로그에 남는다
 * curl ".../api/v11/home/items?value=user-1&timeoutMillis=400"
 *
 * # 동시 호출 상한(3) — 10개를 동시에 던지면 대기가 보인다
 * seq 10 | xargs -P10 -I{} curl -s -o /dev/null ".../api/v11/home/items?value=user-{}"
 * ```
 */
@RestController
@RequestMapping("/api/v11/home")
class HomeItemV11Controller(
    private val homeItemServiceV11: HomeItemServiceV11,
) {

    @GetMapping("/items")
    suspend fun getHomeItems(
        userId: UserId,
        @RequestParam(defaultValue = "false") failFast: Boolean,
        @RequestParam(defaultValue = "${HomeItemServiceV11.DEFAULT_TIMEOUT_MILLIS}") timeoutMillis: Long,
    ): List<HomeItem> {
        return homeItemServiceV11.getHomeItemsV11(userId, failFast, timeoutMillis)
    }
}
