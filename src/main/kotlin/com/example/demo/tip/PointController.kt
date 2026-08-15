package com.example.demo.tip

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val log = LoggerFactory.getLogger(PointController::class.java)

/**
 * 실전 팁 시연용 엔드포인트.
 *
 * ```bash
 * # 1) 동기 + 실패 -> 둘 다 롤백. balance=0, historyCount=0  (정상)
 * curl -X POST ".../api/tips/point/reset?userId=user-1"
 * curl -X POST ".../api/tips/point/charge?userId=user-1&amount=1000&mode=sync&fail=true"
 * sleep 1 && curl ".../api/tips/point?userId=user-1"
 *
 * # 2) 비동기 + 실패 -> 잔액만 롤백. balance=0, historyCount=1  (깨짐)
 * curl -X POST ".../api/tips/point/reset?userId=user-1"
 * curl -X POST ".../api/tips/point/charge?userId=user-1&amount=1000&mode=async&fail=true"
 * sleep 1 && curl ".../api/tips/point?userId=user-1"
 * ```
 *
 * 2번의 `historyCount=1` 이 이 팁의 전부다.
 * 로그에서는 `[insertHistory] 트랜잭션 활성=false` 가 같이 찍힌다.
 */
@RestController
@RequestMapping("/api/tips/point")
class PointController(
    private val pointService: PointService,
) {

    @PostMapping("/charge")
    fun charge(
        @RequestParam userId: String,
        @RequestParam(defaultValue = "1000") amount: Long,
        @RequestParam(defaultValue = "sync") mode: String,
        @RequestParam(defaultValue = "false") fail: Boolean,
    ): Map<String, Any> {
        return try {
            when (mode) {
                "async" -> pointService.chargeAsync(userId, amount, fail)
                else -> pointService.chargeSync(userId, amount, fail)
            }
            mapOf("result" to "committed", "mode" to mode)
        } catch (e: IllegalStateException) {
            log.warn("적립 실패로 롤백됨. mode={} message={}", mode, e.message)
            mapOf("result" to "rolled-back", "mode" to mode, "message" to (e.message ?: ""))
        }
    }

    @GetMapping
    fun status(@RequestParam userId: String): Map<String, Any> = pointService.status(userId)

    @PostMapping("/reset")
    fun reset(@RequestParam userId: String): Map<String, Any> {
        pointService.reset(userId)
        return pointService.status(userId)
    }
}
