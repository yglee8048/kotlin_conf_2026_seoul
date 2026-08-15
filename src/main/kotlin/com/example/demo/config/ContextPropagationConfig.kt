package com.example.demo.config

import com.example.demo.context.CallContextThreadLocalAccessor
import com.example.demo.context.MdcThreadLocalAccessor
import io.micrometer.context.ContextRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.support.ContextPropagatingTaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import reactor.core.publisher.Hooks
import java.util.concurrent.ThreadPoolExecutor

private val log = LoggerFactory.getLogger(ContextPropagationConfig::class.java)

/**
 * 7단계. accessor 를 등록하고, 손으로 짠 decorator 를 걷어낸다.
 *
 * ## 3단계와 무엇이 다른가
 *
 * 3단계에서는 전파 로직 자체를 내가 짰다. ([com.example.demo.context.CallContextTaskDecorator])
 * 캡처·주입·원복을 직접 구현했고, 전파 대상이 늘 때마다 그 클래스를 고쳐야 했다.
 * 게다가 그 decorator 는 **내가 만든 executor 에만** 걸려 있었다.
 *
 * 여기서는 두 줄이면 끝난다.
 *
 * 1. `ThreadLocalAccessor` 를 [ContextRegistry] 에 등록한다.
 * 2. executor 에는 Spring 이 주는 [ContextPropagatingTaskDecorator] 를 건다.
 *
 * 그리고 **코루틴 쪽은 아무것도 안 해도 된다.**
 * Reactor 의 자동 컨텍스트 전파([Hooks.enableAutomaticContextPropagation])가 켜져 있으면
 * Spring MVC 가 suspend
 * 컨트롤러를 호출할 때 CoroutineContext 에 `PropagationContextElement` 를 얹고,
 * 코루틴이 **재개될 때마다** 여기 등록된 accessor 들이 ThreadLocal 을 복원한다.
 * 그래서 `withContext(Dispatchers.IO)` 안에서도 MDC 와 CallContext 가 살아있다.
 *
 * ## 시연용 토글
 *
 * 등록을 런타임에 껐다 켤 수 있게 해뒀다. ([enable] / [disable])
 * 재시작 없이 "전파 안 됨 -> 전파 됨" 을 같은 엔드포인트에서 보여주기 위함이다.
 * `POST /api/demo/context-accessors?enabled=false` 로 끄고 v7 을 때려보면
 * 6단계까지의 상태(= worker 스레드에서 `ctx=없음`)가 그대로 재현된다.
 *
 * ## 한계 (발표에서 짚을 것)
 *
 * 자동 전파가 걸리는 건 **suspend 컨트롤러 경로**다.
 * 5단계처럼 `runBlocking` 으로 감싼 경우에는 `CoroutinesUtils` 를 거치지 않으므로
 * `PropagationContextElement` 가 붙지 않고, 따라서 전파도 되지 않는다.
 * "코루틴을 쓰면 된다" 가 아니라 "**프레임워크가 코루틴 진입점을 알아야 한다**" 는 뜻이다.
 */
@Configuration
class ContextPropagationConfig {

    /**
     * Reactor 의 자동 컨텍스트 전파를 켠다.
     *
     * Spring Boot 3.x 에는 `spring.reactor.context-propagation=auto` 프로퍼티가 있었지만
     * **Boot 4 에는 Reactor 오토컨피그가 없다.** (webflux 를 안 쓰는 이 프로젝트에서는 특히)
     * 그래서 직접 켠다. 이걸 빼먹으면 accessor 를 등록해도 코루틴 쪽 전파가 조용히 안 된다.
     *
     * 이 훅이 `CoroutinesUtils.invokeSuspendingFunction` 의 분기 조건이다.
     * (`Hooks.isAutomaticContextPropagationEnabled()` 가 true 여야
     * `PropagationContextElement` 가 CoroutineContext 에 붙는다)
     */
    @PostConstruct
    fun enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation()
        log.info("Reactor 자동 컨텍스트 전파 활성={}", Hooks.isAutomaticContextPropagationEnabled())
        enable()
    }

    fun enable() {
        ContextRegistry.getInstance()
            .registerThreadLocalAccessor(CallContextThreadLocalAccessor())
            .registerThreadLocalAccessor(MdcThreadLocalAccessor())
        log.info("ThreadLocalAccessor 등록됨: {}", registeredKeys())
    }

    /** 시연용. 등록을 해제하면 6단계까지의 동작(전파 없음)으로 되돌아간다. */
    fun disable() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(CallContextThreadLocalAccessor.KEY)
        ContextRegistry.getInstance().removeThreadLocalAccessor(MdcThreadLocalAccessor.KEY)
        log.info("ThreadLocalAccessor 해제됨: {}", registeredKeys())
    }

    fun registeredKeys(): List<Any> =
        ContextRegistry.getInstance().threadLocalAccessors.map { it.key() }

    /**
     * 7단계용 로그 executor.
     *
     * 3단계의 [ContextAwareAsyncConfig] 와 비교하면 딱 한 줄이 다르다.
     *
     * ```
     * setTaskDecorator(CallContextTaskDecorator())        // 3단계: 내가 짠 것
     * setTaskDecorator(ContextPropagatingTaskDecorator()) // 7단계: Spring 이 주는 것
     * ```
     *
     * 후자는 `ContextSnapshotFactory.captureAll()` 로 **등록된 accessor 전부**를 뜬다.
     * 전파 대상이 늘어도 이 줄은 그대로다.
     */
    @Bean(LOG_TASK_EXECUTOR_V7)
    fun logTaskExecutorV7(): ThreadPoolTaskExecutor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = 5
            maxPoolSize = 10
            queueCapacity = 500
            setThreadNamePrefix("user-log-v7-")
            setRejectedExecutionHandler(ThreadPoolExecutor.DiscardPolicy())
            setTaskDecorator(ContextPropagatingTaskDecorator())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
        }
    }

    companion object {
        const val LOG_TASK_EXECUTOR_V7 = "logTaskExecutorV7"
    }
}
