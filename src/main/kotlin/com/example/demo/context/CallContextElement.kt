package com.example.demo.context

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * [CallContext] 를 코루틴에 **명시적으로** 전파하는 element.
 *
 * 7단계 도입부에서 소개하는 "일단 손으로 하는" 방법이다.
 * 서비스 코드(v5~v7)에는 쓰지 않는다 — accessor 자동 전파가 이걸 대체한다는 것이 7단계의 내용.
 *
 * 3단계의 [CallContextTaskDecorator] 와 정확히 같은 3단계 모델이다. 대상만 다르다.
 *
 * | | decorator (3단계) | element (명시적 전파) |
 * |---|---|---|
 * | 캡처 | `decorate()` — 제출하는 스레드에서 | 생성자 기본값 — element 를 만드는 스레드에서 |
 * | 주입 | Runnable 실행 직전 | [updateThreadContext] — 코루틴이 **재개될 때마다** |
 * | 원복 | `finally` | [restoreThreadContext] — 코루틴이 중단될 때마다 |
 *
 * 코루틴은 suspension 을 넘나들며 스레드를 갈아탈 수 있으므로,
 * 한 번 심는 것이 아니라 **재개/중단마다** 넣었다 뺐다 해준다. 그건 element 가 알아서 한다.
 *
 * MDC 쪽은 같은 일을 하는 [kotlinx.coroutines.slf4j.MDCContext] 가 이미 제공된다.
 * 즉 전파할 ThreadLocal 타입 하나마다 element 하나가 필요하고,
 * 코루틴을 시작하는 지점마다 `+` 로 끼워넣어야 한다.
 * **3단계 decorator 의 부채(대상마다·경계마다 손으로)가 모양만 바꿔 돌아온 것** — 7단계에서 걷어낸다.
 */
class CallContextElement(
    private val callContext: CallContext? = CallContextHolder.get(), // 캡처
) : ThreadContextElement<CallContext?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<CallContextElement>

    override fun updateThreadContext(context: CoroutineContext): CallContext? {
        val previous = CallContextHolder.get()
        CallContextHolder.set(callContext) // 주입 — 재개될 때마다
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: CallContext?) {
        CallContextHolder.set(oldState) // 원복 — 중단될 때마다
    }
}
