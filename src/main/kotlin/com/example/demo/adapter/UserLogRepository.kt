package com.example.demo.adapter

import com.example.demo.domain.UserEvent
import com.example.demo.vo.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(UserLogRepository::class.java)

@Component
class UserLogRepository {

    /**
     * 홈 화면 접속 기록 적재 (mock, 700ms)
     *
     * 응답 본문에 쓰이지 않는다. 1단계에서는 이 700ms 가 그대로 응답 시간에 더해지고,
     * 2단계에서는 응답이 나간 뒤에도 log executor 에서 계속 실행된다.
     */
    fun saveEvent(userId: UserId, userEvent: UserEvent) {
        log.info("[saveEvent] start   userId={} event={} thread={}", userId.value, userEvent, Thread.currentThread())
        Thread.sleep(LATENCY_MILLIS)
        log.info("[saveEvent] end     userId={} event={} thread={}", userId.value, userEvent, Thread.currentThread())
    }

    companion object {
        const val LATENCY_MILLIS = 700L
    }
}
