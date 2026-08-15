package com.example.demo.tip

import com.example.demo.config.AsyncConfig.Companion.LOG_TASK_EXECUTOR
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = LoggerFactory.getLogger(PointService::class.java)

/**
 * 실전 팁: **비동기 호출과 `@Transactional` 을 섞으면 안 되는 이유.**
 *
 * 트랜잭션은 `TransactionSynchronizationManager` 라는 **ThreadLocal** 에 묶여 있다.
 * 3단계에서 다룬 그 ThreadLocal 문제와 정확히 같은 뿌리다.
 * 다만 결과가 로그 한 줄이 비는 정도가 아니라 **데이터 정합성이 깨지는 것**이라 훨씬 나쁘다.
 *
 * 여기서는 "포인트 적립"이라는 쓰기 두 개짜리 작업으로 보여준다.
 *
 * 1. `point_account.balance` 증가
 * 2. `point_history` 이력 적재
 *
 * 둘은 반드시 함께 성공하거나 함께 실패해야 한다.
 */
@Service
class PointService(
    private val pointRepository: PointRepository,
    private val pointAsyncWriter: PointAsyncWriter,
) {

    /**
     * 정상 동작. 두 쓰기가 같은 스레드, 같은 트랜잭션 안에 있다.
     *
     * `fail=true` 면 둘 다 롤백된다. 잔액도 이력도 남지 않는다.
     */
    @Transactional
    fun chargeSync(userId: String, amount: Long, fail: Boolean) {
        log.info("[chargeSync] 시작")
        pointRepository.addBalance(userId, amount)
        pointRepository.insertHistory(userId, amount, "동기 적립")

        if (fail) {
            throw IllegalStateException("적립 후 검증 실패 (의도된 예외)")
        }
    }

    /**
     * **깨지는 동작.** 이력 적재만 `@Async` 로 뺐다.
     *
     * 흔한 동기다. "이력 적재는 느리니까 비동기로 빼자."
     * 그런데 그 순간 이력 적재는 **다른 스레드**에서 돌고,
     * 트랜잭션은 ThreadLocal 이라 따라가지 않는다.
     *
     * 결과:
     * - `fail=true` 여도 이력 행은 **남는다** (커밋되어 버림)
     * - 잔액은 롤백된다
     * - 즉 "적립되지 않았는데 적립 이력만 있는" 상태가 된다
     *
     * 게다가 더 고약한 문제가 하나 더 있다. **읽기 쪽 경합**이다.
     * 비동기 스레드가 이력을 넣는 시점에 바깥 트랜잭션은 아직 커밋 전이라,
     * 만약 비동기 쪽에서 `point_account` 를 읽으면 **커밋 안 된 잔액을 못 본다.**
     * 타이밍에 따라 결과가 달라지므로 테스트로 잡기도 어렵다.
     *
     * ## 그럼 어떻게 하나
     *
     * - 정말 같은 원자성이 필요하면 **비동기로 빼지 않는다.**
     * - 응답만 빠르게 하고 싶으면 커밋 이후로 미룬다.
     *   (`TransactionSynchronization.afterCommit` / `@TransactionalEventListener(AFTER_COMMIT)`)
     * - 코루틴을 쓴다고 해결되지 않는다. `withContext(Dispatchers.IO)` 도 스레드를 옮긴다.
     *   오히려 **suspend 함수에 `@Transactional` 을 붙이는 것이 더 위험하다.**
     *   중간에 스레드가 바뀌어도 컴파일 에러가 나지 않기 때문이다.
     *
     * > 규칙: **트랜잭션 경계 안에서 스레드를 넘기지 않는다.**
     */
    @Transactional
    fun chargeAsync(userId: String, amount: Long, fail: Boolean) {
        log.info("[chargeAsync] 시작")
        pointRepository.addBalance(userId, amount)

        // 여기서 스레드가 갈라진다. 바깥 트랜잭션은 따라가지 않는다.
        //
        // `@Async` 는 프록시 기반이라 같은 클래스 안에서 부르면 동작하지 않는다.
        // 그래서 별도 빈([PointAsyncWriter])으로 뺐다.
        pointAsyncWriter.insertHistoryAsync(userId, amount)

        if (fail) {
            throw IllegalStateException("적립 후 검증 실패 (의도된 예외)")
        }
    }

    fun status(userId: String): Map<String, Any> = mapOf(
        "userId" to userId,
        "balance" to pointRepository.findBalance(userId),
        "historyCount" to pointRepository.countHistory(userId),
    )

    fun reset(userId: String) = pointRepository.reset(userId)
}

/**
 * `@Async` 프록시를 태우기 위한 별도 빈.
 *
 * 같은 클래스 안의 메서드를 호출하면 self-invocation 이라 프록시를 거치지 않고
 * 그냥 동기 실행된다. 이 함정 자체도 발표에서 짚을 만하다.
 * (2단계 `UserLogRepository` 에서 이미 한 번 나온 이야기다)
 */
@Service
class PointAsyncWriter(
    private val pointRepository: PointRepository,
) {

    @Async(LOG_TASK_EXECUTOR)
    fun insertHistoryAsync(userId: String, amount: Long) {
        // 바깥 트랜잭션이 커밋/롤백을 결정하기 전에 끼어들도록 살짝 지연시킨다.
        // 지연이 없어도 결과는 같지만, 로그 순서가 뒤섞여 설명하기 어려워진다.
        Thread.sleep(100)
        pointRepository.insertHistory(userId, amount, "비동기 적립")
    }
}
