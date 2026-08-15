package com.example.demo.context

/**
 * [CallContext] 를 담는 ThreadLocal.
 *
 * 이름을 `RequestContextHolder` 로 하지 않은 이유는 Spring 의
 * `org.springframework.web.context.request.RequestContextHolder` 와 헷갈리기 때문이다.
 *
 * **왜 `InheritableThreadLocal` 이 답이 아닌가**
 *
 * "자식 스레드에 물려주면 되지 않나" 싶지만, `InheritableThreadLocal` 은
 * **스레드가 생성되는 시점**에 부모 값을 복사한다. thread pool 에서는 스레드가
 * 최초 1회만 생성되고 계속 재사용되므로,
 *
 * - 풀 스레드가 처음 만들어질 때의 컨텍스트가 영원히 남고
 * - 이후 다른 요청이 그 스레드를 쓰면 **남의 traceId·userId 를 보게 된다**
 *
 * 전파가 안 되는 것보다 더 나쁘다. 조용히 틀린 값이 찍히기 때문이다.
 * 그래서 pool 환경에서는 "작업 제출 시점에 캡처 → 실행 시점에 주입 → 끝나면 원복" 이
 * 유일하게 맞는 모델이고, 그게 [CallContextTaskDecorator] 다.
 */
object CallContextHolder {
    private val holder = ThreadLocal<CallContext>()

    fun get(): CallContext? = holder.get()

    fun set(context: CallContext?) {
        if (context == null) holder.remove() else holder.set(context)
    }

    fun clear() = holder.remove()
}
