package com.example.demo.config

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors

/**
 * 10단계. 코루틴의 dispatcher 를 가상 스레드 위로 옮긴다.
 *
 * ## [kotlinx.coroutines.Dispatchers.IO] 의 문제
 *
 * `Dispatchers.IO` 는 **기본 64개 스레드로 제한된 플랫폼 스레드 풀**이다.
 * (`kotlinx.coroutines.io.parallelism` 으로 조정)
 *
 * 이 숫자가 있는 이유는 2단계에서 executor 크기를 고민했던 이유와 정확히 같다.
 * 플랫폼 스레드가 비싸기 때문이다. 즉 코루틴을 써도 **blocking 호출을 하는 한**
 * 플랫폼 스레드 개수라는 천장은 그대로 남아 있었다.
 *
 * blocking mock 을 `runInterruptible(Dispatchers.IO)` 로 감싸는 이 프로젝트에서는
 * 동시 요청 65개째부터 IO 디스패처에서 대기가 생긴다.
 *
 * ## 가상 스레드 dispatcher
 *
 * 여기서는 그 천장이 사라진다. 작업마다 가상 스레드를 만들면 되기 때문이다.
 * **2단계에서 했던 "풀 크기를 하위 시스템 capacity 에 맞추는" 고민 중
 * '스레드가 비싸서' 였던 부분이 여기서 소멸한다.**
 *
 * ## 그런데 사라지지 않는 것
 *
 * 하위 시스템의 처리량 한계는 그대로다. 오히려 이제는 상한이 아예 없어서
 * 오픈뱅킹에 동시 호출 3000개를 날릴 수도 있다.
 * 2단계에서 `AbortPolicy` 로 걸어뒀던 상한이 통째로 없어진 셈이다.
 *
 * > 스레드 풀은 '실행 자원 재사용' 과 '동시성 제한' 이라는 서로 다른 두 역할을 겸하고 있었다.
 * > 가상 스레드는 앞의 역할만 없앤다. 뒤의 역할은 **따로 챙겨야 한다.**
 *
 * 그게 11단계 `@ConcurrencyLimit` 이다.
 */
@Configuration
class VirtualThreadConfig {

    /**
     * 가상 스레드 dispatcher.
     *
     * `Dispatchers.LOOM` 같은 건 아직 없어서 executor 를 만들어 붙인다.
     * 이름을 지어주지 않으면 가상 스레드는 이름이 비어 있어 로그에서 구분되지 않는다.
     *
     * `destroyMethod = "close"` 로 컨텍스트 종료 시 dispatcher 를 닫는다.
     * 안 닫으면 executor 가 남는다.
     */
    @Bean(name = [VIRTUAL_THREAD_DISPATCHER], destroyMethod = "close")
    fun virtualThreadDispatcher(): ExecutorCoroutineDispatcher {
        val threadFactory = Thread.ofVirtual()
            .name("vt-dispatch-", 0)
            .factory()

        return Executors.newThreadPerTaskExecutor(threadFactory).asCoroutineDispatcher()
    }

    companion object {
        const val VIRTUAL_THREAD_DISPATCHER = "virtualThreadDispatcher"
    }
}
