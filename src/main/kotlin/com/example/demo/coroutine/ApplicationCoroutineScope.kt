package com.example.demo.coroutine

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.coroutines.CoroutineContext

private val log = LoggerFactory.getLogger(ApplicationCoroutineScope::class.java)

/**
 * 애플리케이션 수명에 묶인 CoroutineScope. **fire-and-forget 전용 스코프**다.
 *
 * ## 왜 필요한가
 *
 * 5단계에서 홈 조회는 `coroutineScope { ... }` 안에서 돌아간다. 구조적 동시성 덕분에
 * 그 안에서 시작한 코루틴은 전부 응답 전에 끝나야 한다. 그런데 접속 기록 적재는
 * **응답이 나간 뒤에도 살아있어야 하는 작업**이다. 요청 스코프의 자식이면 안 된다.
 *
 * 그래서 부모를 명시적으로 갈아탄다. `GlobalScope` 를 쓰지 않는 이유는 아래 세 가지다.
 *
 * ## 1. SupervisorJob — 형제 간 실패 격리
 *
 * 일반 [kotlinx.coroutines.Job] 이면 접속 기록 적재 하나가 실패했을 때
 * 이 스코프의 **다른 요청이 띄운 코루틴까지 전부 취소된다.** API 간 취소가 전파되는 셈이다.
 * [SupervisorJob] 은 자식의 실패를 위로 올리지 않는다.
 *
 * ## 2. CoroutineExceptionHandler — 스코프 단위 예외 처리
 *
 * 2단계에서 `@Async` 의 한계로 지적했던 부분이다.
 * `AsyncConfigurer.getAsyncUncaughtExceptionHandler()` 는 애플리케이션 전역에 딱 하나였고,
 * 받을 수 있는 정보도 Method 와 파라미터 배열뿐이었다.
 *
 * 코루틴에서는 **스코프마다** 핸들러를 붙일 수 있다. "전역이냐 작업별이냐"를 고를 필요가 없다.
 *
 * ## 3. @PreDestroy — 종료 시 취소 전파
 *
 * 서버가 내려갈 때 실행 중이던 코루틴에 취소를 전파한다.
 * `ThreadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true)` 와 대비된다.
 * 그쪽은 "기다린다"밖에 못 하지만, 여기서는 **취소를 보낸 뒤 정리될 때까지만 기다린다.**
 *
 * 취소가 실제로 먹히려면 하위 작업이 취소에 협조해야 한다.
 * blocking mock 은 `runInterruptible` 로 감싸야 인터럽트를 받는다.
 * (`HomeItemServiceV5.blockingIo` 참고)
 */
@Component
class ApplicationCoroutineScope : CoroutineScope {

    private val supervisorJob = SupervisorJob()

    private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        log.warn("백그라운드 코루틴 실패. coroutine={}", context[CoroutineName], throwable)
    }

    override val coroutineContext: CoroutineContext =
        supervisorJob + Dispatchers.IO + CoroutineName("app-scope") + exceptionHandler

    @PreDestroy
    fun shutdown() {
        val remaining = supervisorJob.children.count()
        log.info("애플리케이션 스코프 종료. 실행 중인 코루틴={}", remaining)

        runBlocking {
            // 취소를 전파하고, 정리가 끝날 때까지만 짧게 기다린다.
            val finished = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) {
                supervisorJob.cancelAndJoin()
                true
            }
            if (finished == null) {
                log.warn("코루틴 정리가 {}ms 안에 끝나지 않았다.", SHUTDOWN_TIMEOUT_MILLIS)
            }
        }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_MILLIS = 5_000L
    }
}
