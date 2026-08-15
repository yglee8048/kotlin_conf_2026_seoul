package com.example.demo.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * 로그를 남긴 스레드가 플랫폼 스레드인지 가상 스레드인지 한 글자로 표시한다.
 *
 * - `P` : platform thread
 * - `V` : virtual thread
 *
 * 8단계 이후에 `vt` 프로파일로 재시작하면 **코드는 그대로인데 이 글자만 바뀐다.**
 * 발표에서 "무엇이 달라졌는가"를 가리키기 가장 쉬운 지점이라 로그 패턴에 상시 노출한다.
 *
 * 주의: 이 값은 **로그를 남기는 시점의 스레드** 기준이다. logback 의
 * 비동기 appender 를 쓰면 의미가 없어지지만, 이 프로젝트는 동기 콘솔 appender 만 쓴다.
 */
class ThreadKindConverter : ClassicConverter() {

    override fun convert(event: ILoggingEvent): String =
        if (Thread.currentThread().isVirtual) "V" else "P"
}
