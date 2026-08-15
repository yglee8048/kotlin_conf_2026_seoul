package com.example.demo.context

import io.micrometer.context.ThreadLocalAccessor

/**
 * [CallContext] 를 micrometer context-propagation 에 등록하기 위한 accessor.
 *
 * 3단계에서 손으로 짠 [CallContextTaskDecorator] 와 하는 일은 같다.
 * "지금 스레드에서 값을 꺼내고 / 다른 스레드에 심고 / 원복한다."
 *
 * 결정적인 차이는 **누가 그걸 호출하느냐**다.
 *
 * - 3단계: 내가 만든 decorator 를, 내가 만든 executor 에만 걸었다.
 *   그래서 `Dispatchers.IO` 나 `@Scheduled`, RestClient 의 비동기 경로에는 적용되지 않았다.
 * - 7단계: 여기 한 번 등록해두면 **컨텍스트 경계를 아는 모든 곳**이 알아서 호출한다.
 *   `ContextPropagatingTaskDecorator`, Reactor, 그리고 Spring 7 의 suspend 컨트롤러까지.
 *
 * 즉 3단계의 부채였던 "전파 대상 목록을 손으로 관리한다" 가 "타입마다 accessor 하나 선언한다" 로 바뀐다.
 * 목록을 관리하는 주체가 호출부에서 타입 쪽으로 옮겨간 것이다.
 *
 * [key] 는 이 컨텍스트 값의 식별자다. Reactor Context 에 담길 때 이 키를 쓴다.
 */
class CallContextThreadLocalAccessor : ThreadLocalAccessor<CallContext> {

    override fun key(): Any = KEY

    override fun getValue(): CallContext? = CallContextHolder.get()

    override fun setValue(value: CallContext) = CallContextHolder.set(value)

    /** 값이 없는 상태로 만들어야 할 때. 여기서 지우지 않으면 풀 스레드에 눌어붙는다. */
    override fun setValue() = CallContextHolder.clear()

    companion object {
        const val KEY = "callContext"
    }
}
