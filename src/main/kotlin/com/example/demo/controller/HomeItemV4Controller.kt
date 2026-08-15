package com.example.demo.controller

import com.example.demo.domain.HomeItem
import com.example.demo.service.HomeItemServiceV4
import com.example.demo.vo.UserId
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult

private val log = LoggerFactory.getLogger(HomeItemV4Controller::class.java)

/**
 * 4단계 엔드포인트. [DeferredResult] 를 반환한다.
 *
 * 핵심은 **이 메서드가 200ms 도 안 걸리고 끝난다**는 것이다.
 * 반환하는 순간 서블릿 컨테이너는 요청을 async 모드로 전환하고 톰캣 스레드를 풀에 돌려준다.
 * 응답은 나중에 `setResult` 를 호출하는 스레드(= 여기서는 open-banking / home-info 풀)가 쓴다.
 *
 * 로그의 `[진입]` / `[반환]` 두 줄 사이 간격을 보면 바로 확인된다.
 * v1~v3 는 이 자리에서 800~1800ms 를 붙잡고 있었다.
 *
 * 참고: Spring MVC 는 `CompletableFuture` 를 그대로 반환해도 동일하게 동작한다.
 * 여기서 [DeferredResult] 를 쓴 건 timeout 콜백을 붙여 보여주기 위해서다.
 */
@RestController
@RequestMapping("/api/v4/home")
class HomeItemV4Controller(
    private val homeItemServiceV4: HomeItemServiceV4,
) {

    @GetMapping("/items")
    fun getHomeItems(
        userId: UserId,
        @RequestParam(defaultValue = "false") failFast: Boolean,
    ): DeferredResult<List<HomeItem>> {
        log.info("[v4] 진입")

        val deferredResult = DeferredResult<List<HomeItem>>(TIMEOUT_MILLIS)

        // DeferredResult 의 timeout 은 **응답**에만 걸린다.
        // 이 콜백이 불려도 뒤에서 돌고 있는 CompletableFuture 들은 취소되지 않고 계속 실행된다.
        // "요청 단위 timeout" 과 "작업 단위 취소" 가 분리되어 있다는 뜻이고,
        // 이건 11단계까지 계속 따라다니는 문제다.
        deferredResult.onTimeout {
            log.warn("[v4] 응답 timeout. 단, 뒤의 작업들은 계속 실행 중이다.")
            deferredResult.setErrorResult(IllegalStateException("timeout"))
        }

        homeItemServiceV4.getHomeItemsV4(userId, failFast)
            .whenComplete { homeItems, throwable ->
                if (throwable != null) {
                    deferredResult.setErrorResult(throwable)
                } else {
                    deferredResult.setResult(homeItems)
                }
            }

        log.info("[v4] 반환 (톰캣 스레드 반납)")
        return deferredResult
    }

    companion object {
        private const val TIMEOUT_MILLIS = 3_000L
    }
}
