package com.example.demo.utils

import com.example.demo.context.CallContextHolder
import org.slf4j.Logger

/**
 * 로그에 찍을 현재 스레드의 호출 컨텍스트.
 *
 * 전파가 안 된 스레드에서는 `없음` 이 찍힌다. 3단계 시연의 핵심 관찰 지점이다.
 * - v2: worker 스레드에서 `ctx=없음`
 * - v3: worker 스레드에서도 `ctx=MOBILE/a1b2c3d4`
 */
fun callContextLabel(): String = CallContextHolder.get()?.short() ?: "없음"

/**
 * mock 하위 시스템의 I/O 지연.
 *
 * 그냥 `Thread.sleep` 이 아니라 인터럽트를 로그로 남기는 이유는 **취소를 눈으로 보기 위해서**다.
 *
 * 5단계 이후 코루틴이 취소되면 `runInterruptible` 이 실행 스레드를 인터럽트한다.
 * 그때 이 로그가 찍히고, 짝이 되는 `end` 로그는 찍히지 않는다.
 * "형제 작업이 실제로 멈췄다" 를 증명하는 유일한 증거다.
 *
 * 반대로 2~4단계(CompletableFuture)에서는 이 로그가 **절대 찍히지 않는다.**
 * 하나가 실패해도 나머지는 끝까지 돌기 때문이다. 그 대비가 5단계의 핵심이다.
 */
fun mockLatency(log: Logger, label: String, millis: Long) {
    try {
        Thread.sleep(millis)
    } catch (e: InterruptedException) {
        log.warn("[{}] 취소됨(인터럽트) ctx={}", label, callContextLabel())
        // 인터럽트 상태를 삼키지 않고 다시 던진다.
        // 여기서 삼키면 취소가 상위로 전달되지 않아 구조적 동시성이 깨진다.
        throw e
    }
}
