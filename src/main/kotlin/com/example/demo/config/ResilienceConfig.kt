package com.example.demo.config

import org.springframework.context.annotation.Configuration
import org.springframework.resilience.annotation.EnableResilientMethods

/**
 * 11단계. Spring Framework 7 의 `@ConcurrencyLimit` / `@Retryable` 을 켠다.
 *
 * 별도 라이브러리(resilience4j 등) 없이 spring-context 만으로 동작한다.
 * 프록시 기반이라 `kotlin("plugin.spring")` 이 클래스를 열어주고 있어야 한다.
 * (`@Async` 나 `@Transactional` 과 같은 조건)
 */
@Configuration
@EnableResilientMethods
class ResilienceConfig
