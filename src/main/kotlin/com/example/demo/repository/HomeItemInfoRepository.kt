package com.example.demo.repository

import com.example.demo.domain.HomeCardInfo
import com.example.demo.vo.AccountId
import com.example.demo.vo.HomeCardColor
import com.example.demo.vo.HomeCardImage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(HomeItemInfoRepository::class.java)

@Component
class HomeItemInfoRepository {

    /**
     * 개인화 DB 에서 계좌 이름/색상 조회 (mock, 200ms)
     *
     * 사용자가 개인화하지 않은 계좌는 행이 없으므로, 요청한 계좌보다 적게 반환된다.
     */
    fun getHomeItemInfos(accountIds: List<AccountId>): List<HomeCardInfo> {
        log.info("[getHomeItemInfos] start   size={} thread={}", accountIds.size, Thread.currentThread())
        Thread.sleep(LATENCY_MILLIS)
        log.info("[getHomeItemInfos] end     size={} thread={}", accountIds.size, Thread.currentThread())

        // 마지막 계좌는 개인화 정보가 없는 것으로 두어 기본값 fallback 을 보여준다.
        return accountIds.dropLast(1).mapIndexed { index, accountId ->
            HomeCardInfo(
                accountId = accountId,
                alias = ALIASES[index % ALIASES.size],
                color = HomeCardColor(COLORS[index % COLORS.size]),
                image = HomeCardImage(IMAGES[index % IMAGES.size]),
            )
        }
    }

    companion object {
        const val LATENCY_MILLIS = 200L

        private val ALIASES = listOf("생활비 통장", "비상금 통장", "여행 자금")
        private val COLORS = listOf("mint", "coral", "navy")
        private val IMAGES = listOf("wallet", "piggy-bank", "airplane")
    }
}
