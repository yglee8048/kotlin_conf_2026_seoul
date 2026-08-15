package com.example.demo.context

import com.example.demo.vo.Channel
import com.example.demo.vo.UserId

/**
 * 요청 하나에 묶이는 호출 컨텍스트.
 *
 * MDC 는 `Map<String, String>` 이라 로그에 찍는 용도로는 충분하지만,
 * 비즈니스 로직이 `channel` 로 분기하거나 `userId` 를 꺼내 쓰기에는 부적합하다.
 * (매번 문자열 파싱 + 타입 안전성 없음)
 *
 * 그래서 실무에서는 보통 MDC 와 **별도로** 타입 있는 컨텍스트를 ThreadLocal 에 둔다.
 * 3단계의 문제는 이 두 개를 **둘 다** 전파해야 한다는 것이다.
 */
data class CallContext(
    val traceId: String,
    val channel: Channel,
    val deviceId: String?,
    val userId: UserId?,
) {
    /** 로그 한 줄에 넣기 위한 짧은 표현 */
    fun short(): String = "$channel/$traceId"
}
