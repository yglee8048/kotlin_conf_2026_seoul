package com.example.demo.config

import com.example.demo.context.CallContextTaskDecorator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor

/**
 * 3단계에서 사용하는 Executor 정의.
 *
 * 풀 설정 자체는 [AsyncConfig] 와 완전히 동일하다. 딱 하나만 다르다.
 *
 * ```
 * setTaskDecorator(CallContextTaskDecorator())
 * ```
 *
 * 2단계 executor 를 그대로 두고 별도로 만든 이유는, 발표에서
 * "전파 안 되는 v2" 와 "전파 되는 v3" 를 **동시에 띄워놓고 비교**하기 위해서다.
 * 실무라면 [AsyncConfig] 의 executor 에 decorator 를 추가하고 끝낼 일이다.
 *
 * 스레드 이름에 `-v3-` 가 붙어 있어 로그만 보고도 어느 쪽 풀인지 구분된다.
 *
 * 중요한 점: decorator 는 executor 에 붙는다. 그래서
 * `CompletableFuture.supplyAsync(..., executor)` 든 `@Async("...")` 든
 * **이 executor 를 쓰는 모든 경로에 자동으로 적용된다.** 호출부는 아무것도 안 바뀐다.
 */
@Configuration
class ContextAwareAsyncConfig {

    @Bean(HOME_INFO_TASK_EXECUTOR_V3)
    fun homeInfoTaskExecutorV3(): ThreadPoolTaskExecutor = contextAware(
        threadNamePrefix = "home-info-v3-",
        corePoolSize = 10,
        maxPoolSize = 10,
        queueCapacity = 50,
        rejectedExecutionHandler = ThreadPoolExecutor.CallerRunsPolicy(),
    )

    @Bean(OPEN_BANKING_TASK_EXECUTOR_V3)
    fun openBankingTaskExecutorV3(): ThreadPoolTaskExecutor = contextAware(
        threadNamePrefix = "open-banking-v3-",
        corePoolSize = 30,
        maxPoolSize = 30,
        queueCapacity = 100,
        rejectedExecutionHandler = ThreadPoolExecutor.AbortPolicy(),
    )

    @Bean(LOG_TASK_EXECUTOR_V3)
    fun logTaskExecutorV3(): ThreadPoolTaskExecutor = contextAware(
        threadNamePrefix = "user-log-v3-",
        corePoolSize = 5,
        maxPoolSize = 10,
        queueCapacity = 500,
        rejectedExecutionHandler = ThreadPoolExecutor.DiscardPolicy(),
    )

    /**
     * decorator 를 한 곳에서 모든 executor 에 건다.
     * 전파 대상이 늘어도 고칠 곳은 [CallContextTaskDecorator] 하나다.
     */
    private fun contextAware(
        threadNamePrefix: String,
        corePoolSize: Int,
        maxPoolSize: Int,
        queueCapacity: Int,
        rejectedExecutionHandler: RejectedExecutionHandler,
    ): ThreadPoolTaskExecutor {
        return ThreadPoolTaskExecutor().apply {
            this.corePoolSize = corePoolSize
            this.maxPoolSize = maxPoolSize
            this.queueCapacity = queueCapacity
            setThreadNamePrefix(threadNamePrefix)
            setRejectedExecutionHandler(rejectedExecutionHandler)
            setTaskDecorator(CallContextTaskDecorator())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
        }
    }

    companion object {
        const val HOME_INFO_TASK_EXECUTOR_V3 = "homeInfoTaskExecutorV3"
        const val OPEN_BANKING_TASK_EXECUTOR_V3 = "openBankingTaskExecutorV3"
        const val LOG_TASK_EXECUTOR_V3 = "logTaskExecutorV3"
    }
}
