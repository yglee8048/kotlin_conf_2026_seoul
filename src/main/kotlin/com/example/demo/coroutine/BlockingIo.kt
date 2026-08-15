package com.example.demo.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * blocking 호출을 코루틴에서 실행한다.
 *
 * ## 왜 `withContext(Dispatchers.IO)` 가 아니라 [runInterruptible] 인가
 *
 * 코루틴 취소는 **협조적**이다. 취소되면 다음 suspension point 에서 `CancellationException`
 * 이 던져질 뿐이고, `Thread.sleep()` 이나 JDBC 호출처럼 스레드를 붙잡고 있는 blocking
 * 코드는 suspension point 가 없어서 **취소를 알아채지 못한다.**
 *
 * ```
 * withContext(Dispatchers.IO) { Thread.sleep(5000) }   // 취소해도 5초를 다 채운다
 * runInterruptible(Dispatchers.IO) { Thread.sleep(5000) } // 취소되면 즉시 인터럽트
 * ```
 *
 * [runInterruptible] 은 취소 시 **실행 중인 스레드를 인터럽트**한다.
 * 그래서 `InterruptedException` 을 존중하는 blocking 코드는 즉시 멈춘다.
 *
 * 이게 없으면 5단계에서 "구조적 동시성으로 형제가 취소된다" 를 시연할 수 없다.
 * 취소는 전파되지만 정작 스레드는 계속 자고 있기 때문이다.
 *
 * 뒤집어 말하면, 라이브러리가 `InterruptedException` 을 삼키면 여기서도 방법이 없다.
 * **취소 가능성은 공짜가 아니라 하위 계층까지 이어져야 하는 계약이다.**
 *
 * @param dispatcher 10단계에서 [Dispatchers.IO] 대신 가상 스레드 dispatcher 를 넣는다.
 */
suspend fun <T> blockingIo(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: () -> T,
): T = runInterruptible(dispatcher, block = block)
