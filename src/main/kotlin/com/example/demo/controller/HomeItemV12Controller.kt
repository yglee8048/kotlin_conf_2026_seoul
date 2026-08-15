package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemServiceV12
import com.example.demo.vo.UserId
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val log = LoggerFactory.getLogger(HomeItemV12Controller::class.java)

/**
 * 12단계 엔드포인트. **평범한 blocking 컨트롤러다.**
 *
 * 1단계와 시그니처가 같다. 그런데 안에서는 병렬 실행되고, 구조적 동시성도 있고,
 * 묶음 timeout 도 걸려 있다. `vt` 프로파일이면 이 스레드마저 가상 스레드다.
 *
 * 이 단계에서 하고 싶은 말은 "코루틴 없이도 여기까지 온다" 이고,
 * 동시에 "그래서 코루틴이 필요 없는가" 에 대한 답은 [HomeItemServiceV12] 의 비교표에 있다.
 *
 * ```bash
 * ./gradlew bootRun --args='--spring.profiles.active=vt'
 * curl ".../api/v12/home/items?value=user-1"
 * curl ".../api/v12/home/items?value=user-1&timeoutMillis=400"
 * curl ".../api/v12/home/items?value=user-1&failFast=true"
 * ```
 */
@RestController
@RequestMapping("/api/v12/home")
class HomeItemV12Controller(
    private val homeItemServiceV12: HomeItemServiceV12,
) {

    @GetMapping("/items")
    fun getHomeItems(
        userId: UserId,
        @RequestParam(defaultValue = "false") failFast: Boolean,
        @RequestParam(defaultValue = "${HomeItemServiceV12.DEFAULT_TIMEOUT_MILLIS}") timeoutMillis: Long,
    ): List<HomeItem> {
        log.info("[v12] 진입")
        val homeItems = homeItemServiceV12.getHomeItemsV12(userId, failFast, timeoutMillis)
        log.info("[v12] 반환 (진입과 같은 스레드다 — join 이 막고 있었다)")
        return homeItems
    }
}
