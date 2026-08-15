package com.example.demo

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.StructuredTaskScope
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 12단계가 쓰는 preview API 가 살아있는지 확인하는 가드.
 *
 * `--enable-preview` 는 build.gradle.kts 에서 컴파일과 실행 양쪽에 걸어둔다.
 * 어느 한쪽이라도 빠지면 `/api/v12` 가 런타임에 죽는데, 발표 중에 발견하면 늦는다.
 *
 * 두 번째 테스트는 Kotlin 에서만 나는 함정을 고정한다.
 * `scope.fork { ... }` 로 쓰면 `fork(Runnable)` 오버로드가 선택되어 결과가 null 이 된다.
 * 반드시 `fork(Callable { ... })` 로 명시해야 한다.
 */
class PreviewFeatureEnabledTest {

    @Test
    fun `fork 한 작업의 결과를 모아온다`() {
        StructuredTaskScope.open<Any, Void>(
            StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(),
        ).use { scope ->
            val fast = scope.fork(Callable { Thread.sleep(50); "A" })
            val slow = scope.fork(Callable { Thread.sleep(200); "B" })

            scope.join()

            assertEquals("AB", "${fast.get()}${slow.get()}")
        }
    }

    @Test
    fun `withTimeout 이 묶음 전체에 걸린다`() {
        assertFailsWith<StructuredTaskScope.TimeoutException> {
            StructuredTaskScope.open<Any, Void>(
                StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(),
                { config -> config.withTimeout(Duration.ofMillis(200)) },
            ).use { scope ->
                scope.fork(Callable { Thread.sleep(5_000); "느림" })
                scope.join()
            }
        }
    }
}
