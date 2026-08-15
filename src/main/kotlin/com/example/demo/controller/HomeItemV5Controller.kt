package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemServiceV5
import com.example.demo.vo.UserId
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val log = LoggerFactory.getLogger(HomeItemV5Controller::class.java)

/**
 * 5단계 엔드포인트. **1단계 컨트롤러와 완전히 같은 모양이다.**
 *
 * `runBlocking` 은 여기에 없다. 서비스 안쪽, 병렬 조회가 필요한 그 구간에만 있다.
 * ([com.example.demo.service.HomeItemServiceV5.getHomeItemsV5])
 *
 * 즉 컨트롤러 입장에서 5단계는 **아무 일도 일어나지 않은 것처럼 보인다.**
 * 반환 타입도 `List<HomeItem>` 그대로다. 4단계에서 `DeferredResult` 로 바꿔야 했던 것과 대비된다.
 *
 * 대가로 톰캣 스레드는 응답이 완성될 때까지 그대로 붙잡혀 있다.
 * 스레드 점유는 2·3단계와 같고, 4단계보다 오히려 나쁘다.
 *
 * **이건 일부러 이렇게 둔 것이다.** 5단계에서 얻는 것은 코드 구조와 구조적 동시성뿐이고,
 * 스레드 반납은 6단계에서 `runBlocking` 을 걷어내는 것만으로 따라온다는 걸 보이기 위함이다.
 */
@RestController
@RequestMapping("/api/v5/home")
class HomeItemV5Controller(
    private val homeItemServiceV5: HomeItemServiceV5,
) {

    @GetMapping("/items")
    fun getHomeItems(
        userId: UserId,
        @RequestParam(defaultValue = "false") failFast: Boolean,
    ): List<HomeItem> {
        log.info("[v5] 진입")
        val homeItems = homeItemServiceV5.getHomeItemsV5(userId, failFast)
        log.info("[v5] 반환 (여기까지 톰캣 스레드를 붙잡고 있었다)")
        return homeItems
    }
}
