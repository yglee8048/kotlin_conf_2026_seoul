package com.example.demo.context

import io.micrometer.context.ThreadLocalAccessor
import org.slf4j.MDC

/**
 * MDC 전체 맵을 옮기는 accessor.
 *
 * [CallContextThreadLocalAccessor] 와 **별개로 하나 더 필요하다**는 점이 중요하다.
 * 전파해야 할 것이 두 종류면 accessor 도 두 개다.
 *
 * 다만 3단계와 다른 점은, 이제 이 목록이 늘어나도
 * **호출부나 executor 설정은 건드리지 않는다**는 것이다.
 * SecurityContext 를 추가하고 싶으면 accessor 클래스 하나를 더 등록하면 끝이다.
 *
 * MDC 를 맵 통째로 다루는 이유는 traceId 외에 channel 등 다른 키도 함께 들어있기 때문이다.
 * 키 하나씩 accessor 를 만들면 개수만 늘고 얻는 게 없다.
 */
class MdcThreadLocalAccessor : ThreadLocalAccessor<Map<String, String>> {

    override fun key(): Any = KEY

    override fun getValue(): Map<String, String>? = MDC.getCopyOfContextMap()

    override fun setValue(value: Map<String, String>) = MDC.setContextMap(value)

    override fun setValue() = MDC.clear()

    companion object {
        const val KEY = "mdc"
    }
}
