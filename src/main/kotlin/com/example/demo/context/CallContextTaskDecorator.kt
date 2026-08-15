package com.example.demo.context

import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

/**
 * ThreadLocal 기반 컨텍스트를 executor 스레드로 옮기는 decorator.
 *
 * 모델은 세 단계다.
 *
 * 1. **캡처** — `decorate()` 는 작업을 *제출하는* 스레드(= 톰캣 스레드)에서 호출된다.
 *    이 시점의 컨텍스트를 값으로 붙잡아 둔다.
 * 2. **주입** — 반환한 Runnable 은 *풀* 스레드에서 실행된다. 붙잡아 둔 값을 심는다.
 * 3. **원복** — 풀 스레드는 재사용된다. `finally` 에서 반드시 되돌린다.
 *
 * 3번을 빼먹으면 컨텍스트가 풀 스레드에 눌어붙어, 다음에 그 스레드를 쓰는
 * **다른 요청이 남의 traceId 와 userId 를 보게 된다.** 전파가 안 되는 것보다 나쁘다.
 *
 * 원복을 `clear()` 가 아니라 "이전 값으로 되돌리기" 로 한 이유는,
 * CallerRunsPolicy 때문에 이 Runnable 이 **톰캣 스레드에서 직접 실행될 수도 있기 때문**이다.
 * 그때 `clear()` 를 하면 아직 처리 중인 원래 요청의 컨텍스트를 지워버린다.
 * (2단계에서 개인화 DB executor 에 CallerRuns 를 걸어둔 것이 여기서 실제로 문제가 된다)
 *
 * 한계도 분명하다. 전파할 것이 하나 늘 때마다 이 클래스를 고쳐야 한다.
 * MDC, CallContext, SecurityContext, transaction 동기화... 각각이 별도 항목이다.
 * 7단계에서 Spring 7 의 context accessor 가 이 목록을 없애준다.
 */
class CallContextTaskDecorator : TaskDecorator {

    override fun decorate(runnable: Runnable): Runnable {
        // --- 여기는 제출하는 스레드 (톰캣) ---
        val capturedContext = CallContextHolder.get()
        val capturedMdc = MDC.getCopyOfContextMap()

        return Runnable {
            // --- 여기는 실행하는 스레드 (풀) ---
            val previousContext = CallContextHolder.get()
            val previousMdc = MDC.getCopyOfContextMap()
            try {
                CallContextHolder.set(capturedContext)
                applyMdc(capturedMdc)

                runnable.run()
            } finally {
                CallContextHolder.set(previousContext)
                applyMdc(previousMdc)
            }
        }
    }

    private fun applyMdc(contextMap: Map<String, String>?) {
        if (contextMap == null) MDC.clear() else MDC.setContextMap(contextMap)
    }
}
