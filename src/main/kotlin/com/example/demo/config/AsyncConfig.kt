package com.example.demo.config

import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

private val log = LoggerFactory.getLogger(AsyncConfig::class.java)

/**
 * 2단계에서 사용하는 Executor 정의.
 *
 * 하나의 Executor 를 애플리케이션 전체가 공유하면, 느린 하위 시스템 하나가
 * 풀을 다 먹고 나머지 작업의 큐 대기 시간까지 늘린다. (bulkhead 가 없는 상태)
 * 그래서 **호출하는 하위 시스템 단위로** 분리하고, 각 풀의 크기를 그 하위 시스템의
 * 실제 capacity 에 맞춘다.
 *
 * - [HOME_INFO_TASK_EXECUTOR]   : 개인화 DB 조회. 한계는 DB connection pool.
 * - [OPEN_BANKING_TASK_EXECUTOR]: 외부 오픈뱅킹 HTTP 호출. 한계는 상대 시스템의 처리량과 HTTP connection pool.
 * - [LOG_TASK_EXECUTOR]         : 접속 기록 적재. 응답 경로 밖.
 *
 * 여기서 정한 숫자들은 결국 "스레드가 비싸서" 필요한 튜닝이다.
 * 8단계에서 Virtual Thread 를 도입하면 이 중 어떤 것이 사라지고 어떤 것이 남는지 다시 본다.
 * (재사용 목적은 사라지고, 희소 자원에 대한 동시성 제한은 남는다.)
 */
@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer {

    /**
     * 개인화 DB 조회용.
     * DB connection pool 보다 크게 잡아봐야 connection 대기로 바뀔 뿐이라 pool 크기에 맞춘다.
     * 큐가 가득 차면 호출 스레드(= 톰캣 스레드)가 직접 실행해 순차 호출로 degrade 시킨다.
     */
    @Bean(HOME_INFO_TASK_EXECUTOR)
    fun homeInfoTaskExecutor(): ThreadPoolTaskExecutor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = 10
            maxPoolSize = 10
            queueCapacity = 50
            setThreadNamePrefix("home-info-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())  // 큐가 가득 찬 경우 톰캣 스레드 실행
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
        }
    }

    /**
     * 오픈뱅킹 외부 호출용.
     * DB 조회보다 느리고(500ms) I/O 대기 비중이 크므로 더 크게 잡는다.
     *
     * 여기서는 [ThreadPoolExecutor.AbortPolicy] 다. CallerRuns 와 헷갈리기 쉬운데,
     * 두 정책은 목적이 정반대다.
     *
     * - CallerRuns  : 큐가 차면 호출 스레드가 대신 실행한다. 작업이 버려지지 않는 대신
     *                 **동시성 상한이 새어나간다**. 상대 시스템 입장에서 동시 호출 수는
     *                 30 이 아니라 `30 + 현재 밀어넣는 톰캣 스레드 수` 가 된다.
     * - Abort       : 큐가 차면 RejectedExecutionException 으로 거절한다.
     *                 동시 호출 수가 30 을 넘지 않는다는 보장이 실제로 유지된다.
     *
     * 목표가 "상대 시스템에 대한 동시 호출 상한" 이라면 CallerRuns 는 그 목표를 깨뜨린다.
     * 그래서 거절하고, 거절을 장애가 아니라 과부하 신호로 다루는 것이 맞다.
     *
     * 주의: `CompletableFuture.supplyAsync(supplier, executor)` 는 거절 시
     * RejectedExecutionException 을 **호출 스레드에서 동기로** 던진다.
     * Future 안에 담기지 않으므로 supplyAsync 호출 자체를 감싸야 한다.
     *
     * 발표 포인트: thread pool 은 애초에 동시성 제한 도구로 쓰기 불편하다.
     * 정책이 새거나(CallerRuns) 버리거나(Abort) 둘 중 하나다.
     * 11단계 `@ConcurrencyLimit` 이 필요한 이유가 여기에 있다.
     */
    @Bean(OPEN_BANKING_TASK_EXECUTOR)
    fun openBankingTaskExecutor(): ThreadPoolTaskExecutor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = 30
            maxPoolSize = 30
            queueCapacity = 100
            setThreadNamePrefix("open-banking-")
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())   // 큐가 가득 찬 경우 reject
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
            setRejectedExecutionHandler(ThreadPoolExecutor.DiscardPolicy()) // 큐가 가득 찬 경우 예외 없이 버린다
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
        }
    }

    /**
     * 반환 타입이 void 인 `@Async` 메서드는 호출자가 잡을 Future 자체가 없다.
     * 여기서 받지 않으면 예외가 조용히 사라진다.
     *
     * 단 이건 **애플리케이션 전역에 하나뿐인** 핸들러다. 받을 수 있는 정보도
     * `Method` 와 파라미터 배열뿐이라 작업별 문맥을 담을 수 없다.
     * 그래서 작업별 처리는 메서드 안에서 하고
     * (`UserLogRepository.saveEventAsync` 참고) 여기는 **누락 방지용 최후 그물**로만 둔다.
     *
     * 발표 포인트: fire-and-forget 의 실패는 "누가 알게 되는가"를 별도로 설계해야 한다.
     * Coroutine 에서는 이것이 CoroutineExceptionHandler / SupervisorJob 의 자리이고,
     * 스코프 단위로 붙일 수 있어서 전역/작업별 중 하나를 고르지 않아도 된다.
     */
    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler {
        return AsyncUncaughtExceptionHandler { throwable, method, params ->
            log.warn("@Async 실행 실패. method={} params={}", method.name, params.contentToString(), throwable)
        }
    }

    companion object {
        const val HOME_INFO_TASK_EXECUTOR = "homeInfoTaskExecutor"
        const val OPEN_BANKING_TASK_EXECUTOR = "openBankingTaskExecutor"
        const val LOG_TASK_EXECUTOR = "logTaskExecutor"
    }
}
