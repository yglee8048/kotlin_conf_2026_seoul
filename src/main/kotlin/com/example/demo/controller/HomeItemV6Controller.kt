package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemServiceV6
import com.example.demo.vo.UserId
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val log = LoggerFactory.getLogger(HomeItemV6Controller::class.java)

/**
 * 6단계 엔드포인트. **[HomeItemV5Controller] 에 `suspend` 를 붙인 것이 전부다.**
 *
 * 서비스 쪽도 `runBlocking` 을 걷어냈을 뿐이다.
 * ([com.example.demo.service.HomeItemServiceV5] vs [HomeItemServiceV6])
 *
 * ## 어떻게 동작하나
 *
 * Spring MVC 는 컨트롤러 메서드가 suspend 면 `CoroutinesUtils.invokeSuspendingFunction` 으로
 * 호출해 `Mono` 로 감싸고, 그 결과를 비동기 응답으로 처리한다.
 * 즉 내부적으로는 4단계와 같은 서블릿 async 이지만 **코드에는 드러나지 않는다.**
 *
 * 첫 suspension 지점에서 톰캣 스레드가 반납된다. 로그의 `[진입]` 과 `[반환]` 이
 * **서로 다른 스레드**에서 찍히는 것으로 확인할 수 있다. (5단계에서는 같은 스레드였다)
 *
 * ## 4단계와 비교
 *
 * | | 4단계 | 6단계 |
 * |---|---|---|
 * | 톰캣 스레드 점유 | 320ms (코어뱅킹 구간) | ~1ms |
 * | 코드가 위에서 아래로 | X | O |
 * | 구조적 동시성 | X | O |
 * | 컨트롤러 반환 타입 | `DeferredResult<T>` | `T` |
 * | 하위 시스템마다 executor | 필요 (3개) | 불필요 — dispatcher 하나 |
 *
 * 비동기가 되었는데 **시그니처는 1단계와 같다.**
 *
 * ## 주의
 *
 * `Dispatchers.IO` 로 넘어간 순간 MDC 와 CallContext 는 여전히 사라진다.
 * suspend 로 바꾼다고 컨텍스트 전파가 따라오지는 않는다. -> 7단계
 */
@RestController
@RequestMapping("/api/v6/home")
class HomeItemV6Controller(
    private val homeItemServiceV6: HomeItemServiceV6,
) {

    @GetMapping("/items")
    suspend fun getHomeItems(
        userId: UserId,
        @RequestParam(defaultValue = "false") failFast: Boolean,
    ): List<HomeItem> {
        log.info("[v6] 진입")
        val homeItems = homeItemServiceV6.getHomeItemsV6(userId, failFast)
        log.info("[v6] 반환 (진입과 다른 스레드다)")
        return homeItems
    }
}
