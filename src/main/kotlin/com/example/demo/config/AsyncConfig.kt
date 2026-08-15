package com.example.demo.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

/**
 * 2단계에서 사용하는 Executor 정의.
 *
 * 하나의 Executor 를 애플리케이션 전체가 공유하면 느린 하위 시스템 하나가
 * 나머지 작업의 큐 대기 시간까지 늘린다. 그래서 용도별로 분리한다.
 *
 * - [QUERY_TASK_EXECUTOR] : 응답에 필요한 조회. 호출자가 join() 으로 기다린다.
 * - [LOG_TASK_EXECUTOR]   : 응답과 무관한 적재. 호출자가 기다리지 않는다.
 */
@Configuration
class AsyncConfig {

    /**
     * 조회용. 응답 지연에 직접 영향을 주므로 큐를 짧게 두고,
     * 큐가 가득 차면 호출 스레드(= 톰캣 스레드)가 직접 실행해 순차 호출로 degrade 시킨다.
     */
    @Bean(QUERY_TASK_EXECUTOR)
    fun queryTaskExecutor(): ThreadPoolTaskExecutor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = 20
            maxPoolSize = 40
            queueCapacity = 50
            setThreadNamePrefix("query-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
        }
    }

    /**
     * 로그 적재용. 응답 경로 밖이라 큐를 길게 잡아도 되지만,
     * 서버 종료 시 적재 중이던 작업이 유실되지 않도록 graceful shutdown 을 켠다.
     */
    @Bean(LOG_TASK_EXECUTOR)
    fun logTaskExecutor(): ThreadPoolTaskExecutor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = 5
            maxPoolSize = 10
            queueCapacity = 500
            setThreadNamePrefix("user-log-")
            // 로그는 유실되어도 응답에 영향을 주지 않는다. 큐가 넘치면 버린다.
            setRejectedExecutionHandler(ThreadPoolExecutor.DiscardPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
        }
    }

    companion object {
        const val QUERY_TASK_EXECUTOR = "queryTaskExecutor"
        const val LOG_TASK_EXECUTOR = "logTaskExecutor"
    }
}
