package com.example.demo.tip

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager

private val log = LoggerFactory.getLogger(PointRepository::class.java)

/**
 * 실전 팁 예제용 저장소. DB 쓰기가 두 군데(잔액 갱신 / 이력 적재)인 것이 핵심이다.
 *
 * 매 쓰기마다 **현재 스레드에 트랜잭션이 실제로 붙어 있는지**를 로그로 남긴다.
 * `TransactionSynchronizationManager` 는 ThreadLocal 기반이라,
 * 다른 스레드로 넘어가는 순간 `false` 가 찍힌다. 그게 이 예제가 보여주려는 전부다.
 */
@Repository
class PointRepository(
    private val jdbcClient: JdbcClient,
) {

    fun addBalance(userId: String, amount: Long) {
        logTransactionState("addBalance")
        jdbcClient.sql("UPDATE point_account SET balance = balance + :amount WHERE user_id = :userId")
            .param("amount", amount)
            .param("userId", userId)
            .update()
    }

    fun insertHistory(userId: String, amount: Long, memo: String) {
        logTransactionState("insertHistory")
        jdbcClient.sql("INSERT INTO point_history (user_id, amount, memo) VALUES (:userId, :amount, :memo)")
            .param("userId", userId)
            .param("amount", amount)
            .param("memo", memo)
            .update()
    }

    fun findBalance(userId: String): Long =
        jdbcClient.sql("SELECT balance FROM point_account WHERE user_id = :userId")
            .param("userId", userId)
            .query(Long::class.java)
            .single()

    fun countHistory(userId: String): Long =
        jdbcClient.sql("SELECT COUNT(*) FROM point_history WHERE user_id = :userId")
            .param("userId", userId)
            .query(Long::class.java)
            .single()

    fun reset(userId: String) {
        jdbcClient.sql("DELETE FROM point_history WHERE user_id = :userId")
            .param("userId", userId)
            .update()
        jdbcClient.sql("UPDATE point_account SET balance = 0 WHERE user_id = :userId")
            .param("userId", userId)
            .update()
    }

    private fun logTransactionState(label: String) {
        log.info(
            "[{}] 트랜잭션 활성={} 이름={}",
            label,
            TransactionSynchronizationManager.isActualTransactionActive(),
            TransactionSynchronizationManager.getCurrentTransactionName(),
        )
    }
}
