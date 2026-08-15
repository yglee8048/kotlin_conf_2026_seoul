package com.example.demo.controller

import com.example.demo.config.ContextPropagationConfig
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 발표 중 재시작 없이 상태를 바꾸기 위한 시연용 엔드포인트. 데모 전용이다.
 *
 * 7단계에서 "accessor 를 선언하기 전 / 후" 를 같은 엔드포인트로 비교하기 위해 만들었다.
 * 슬라이드에서 코드만 보여주는 것보다, 같은 curl 을 두 번 때려서 로그가 바뀌는 걸
 * 보여주는 쪽이 훨씬 빠르다.
 */
@RestController
@RequestMapping("/api/demo")
class DemoToggleController(
    private val contextPropagationConfig: ContextPropagationConfig,
) {

    @GetMapping("/context-accessors")
    fun status(): Map<String, Any> = mapOf(
        "registered" to contextPropagationConfig.registeredKeys().map { it.toString() },
    )

    @PostMapping("/context-accessors")
    fun toggle(@RequestParam enabled: Boolean): Map<String, Any> {
        if (enabled) contextPropagationConfig.enable() else contextPropagationConfig.disable()
        return status()
    }
}
