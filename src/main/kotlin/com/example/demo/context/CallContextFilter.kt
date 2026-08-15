package com.example.demo.context

import com.example.demo.vo.Channel
import com.example.demo.vo.UserId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청 진입 시 [CallContext] 와 MDC 를 채우고, 나갈 때 지운다.
 *
 * **이 필터는 v1 / v2 / v3 전부에 똑같이 적용된다.** 그래서 세 버전 모두
 * 톰캣 스레드에서는 컨텍스트가 정상적으로 보인다. 차이는 오직
 * "worker 스레드로 넘어갈 때 따라가는가" 뿐이고, 그게 3단계의 주제다.
 *
 * `finally` 에서 지우는 것이 필수다. 톰캣 스레드도 풀에서 재사용되므로
 * 안 지우면 다음 요청이 이전 요청의 traceId 를 물고 간다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CallContextFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val context = CallContext(
            traceId = request.getHeader(HEADER_TRACE_ID) ?: newTraceId(),
            channel = Channel.from(request.getHeader(HEADER_CHANNEL)),
            deviceId = request.getHeader(HEADER_DEVICE_ID),
            // 데모의 홈 조회는 userId 를 UserId data class 바인딩으로 받으므로
            // 쿼리 파라미터 이름이 `value` 다. 실제 서비스라면 인증 주체에서 꺼낸다.
            userId = request.getParameter("value")?.let { UserId(it) },
        )

        try {
            CallContextHolder.set(context)
            MDC.put(MDC_TRACE_ID, context.traceId)
            MDC.put(MDC_CHANNEL, context.channel.name)
            response.setHeader(HEADER_TRACE_ID, context.traceId)

            filterChain.doFilter(request, response)
        } finally {
            CallContextHolder.clear()
            MDC.clear()
        }
    }

    private fun newTraceId(): String = UUID.randomUUID().toString().substring(0, 8)

    companion object {
        const val HEADER_TRACE_ID = "X-Trace-Id"
        const val HEADER_CHANNEL = "X-Channel"
        const val HEADER_DEVICE_ID = "X-Device-Id"

        const val MDC_TRACE_ID = "traceId"
        const val MDC_CHANNEL = "channel"
    }
}
