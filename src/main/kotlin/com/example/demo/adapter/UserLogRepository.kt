package com.example.demo.adapter

import com.example.demo.config.AsyncConfig.Companion.LOG_TASK_EXECUTOR
import com.example.demo.domain.UserEvent
import com.example.demo.vo.UserId
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(UserLogRepository::class.java)

@Component
class UserLogRepository {

    /**
     * 홈 화면 접속 기록 적재 (mock, 700ms)
     *
     * 응답 본문에 쓰이지 않는다. 1단계에서는 이 700ms 가 그대로 응답 시간에 더해진다.
     */
    fun saveEvent(userId: UserId, userEvent: UserEvent) {
        log.info("[saveEvent] start   userId={} event={} thread={}", userId.value, userEvent, Thread.currentThread())
        Thread.sleep(LATENCY_MILLIS)
        log.info("[saveEvent] end     userId={} event={} thread={}", userId.value, userEvent, Thread.currentThread())
    }

    /**
     * 2단계에서 쓰는 비동기 진입점. 호출 즉시 반환하고 [LOG_TASK_EXECUTOR] 에서 실행된다.
     *
     * **예외를 여기서 직접 잡는 이유**
     *
     * `AsyncConfigurer.getAsyncUncaughtExceptionHandler()` 는 애플리케이션 전역에 하나뿐이라
     * "접속 기록 적재가 실패했다" 같은 도메인 문맥을 담을 수 없다. 전역 핸들러가 받을 수 있는 건
     * Method 와 파라미터 배열뿐이다.
     *
     * void 반환 `@Async` 에서 이 작업 전용으로 실패를 다루려면 결국 메서드 안에서 잡는 것이
     * 가장 단순하다. 잡고 나면 전역 핸들러는 호출되지 않는다. (검증함)
     *
     * 참고로 executor 에 `TaskDecorator` 를 걸어 감싸는 방법은 **동작하지 않는다**.
     * Spring 의 `AsyncExecutionAspectSupport` 가 submit 하는 task 안에서 이미 예외를 잡아
     * 전역 핸들러로 넘기기 때문에, decorator 의 try/catch 까지 예외가 올라오지 않는다. (검증함)
     *
     * 그 외 `@Async` 의 성질:
     * - **프록시 기반**이다. 아래 `saveEvent(...)` 는 같은 인스턴스 직접 호출(self-invocation)이라
     *   프록시를 거치지 않고, 이 메서드를 실행 중인 log executor 스레드에서 그대로 동기 실행된다.
     * - 취소 handle 이 없다. 원 요청이 끊겨도 이 작업은 끝까지 실행된다.
     */
    @Async(LOG_TASK_EXECUTOR)
    fun saveEventAsync(userId: UserId, userEvent: UserEvent) {
        try {
            saveEvent(userId, userEvent)
        } catch (t: Throwable) {
            log.warn("홈 화면 접속 기록 적재 실패. userId={} event={}", userId.value, userEvent, t)
        }
    }

    companion object {
        const val LATENCY_MILLIS = 700L
    }
}
