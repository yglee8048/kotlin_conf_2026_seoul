# 8단계. Virtual Thread — 1단계 코드를 그대로 다시

> 애플리케이션 코드는 한 글자도 바뀌지 않는다. 설정 한 줄이다.

## 코드

`src/main/resources/application-vt.yaml` — 이게 전부다.

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

```bash
./gradlew bootRun --args='--spring.profiles.active=vt'
```

**엔드포인트도 그대로 `/api/v1/home/items` 를 쓴다.** 8단계용 새 코드는 없다.
그게 이 단계에서 하고 싶은 말의 전부다.

## 측정 결과

```
### 프로파일 없음 (7단계까지)
23.690 P[http-nio-8080-exec-6] [trace=TR-V1] CoreBankAdapter : [getAccounts] start
                                             ... 전 구간 같은 플랫폼 스레드 ...
total = 1.84s

### vt 프로파일
57.274 V[tomcat-handler-0    ] [trace=VT-1 ] CoreBankAdapter        : [getAccounts] start
57.581 V[tomcat-handler-0    ] [trace=VT-1 ] HomeItemInfoRepository : [getHomeItemInfos] start
57.783 V[tomcat-handler-0    ] [trace=VT-1 ] OpenBankingAdapter     : [getBalances] start
58.288 V[tomcat-handler-0    ] [trace=VT-1 ] UserLogRepository      : [saveEvent] start
total = 1.89s
```

바뀐 것은 **로그 첫 글자 `P` → `V`** 와 스레드 이름뿐이다.
응답 시간은 그대로 1.9초다.

## 말할 내용

### 1. 응답 시간이 안 줄어든다는 것부터 말한다

**가상 스레드는 지연을 줄이지 않는다.** 1.7초 걸리는 I/O 는 여전히 1.7초 걸린다.
이걸 먼저 인정하고 시작해야 나머지 이야기가 산다.

바뀐 것은 **그 1.7초 동안 무엇을 붙잡고 있느냐**다.

| | 플랫폼 스레드 | 가상 스레드 |
|---|---|---|
| 스택 | ~1MB (고정) | 힙에 저장, 필요한 만큼 |
| 생성 비용 | 비쌈 (그래서 풀링) | 쌈 (그래서 풀링 안 함) |
| 개수 상한 | 수천 | 수백만 |
| I/O 대기 중 | OS 스레드 점유 | **캐리어 스레드 반납** |

### 2. 1단계로 돌아가서 Little's Law 를 다시 본다

1단계에서 이렇게 말했다.

> 초당 1000 요청 × 1.7초 = 시스템 안에 평균 1700개의 요청.
> 톰캣 스레드가 200개면 나머지는 큐에서 대기한다.

가상 스레드에서는 1700개가 그냥 존재한다. 스레드 풀 크기라는 제약이 사라진다.
**1단계의 "평범한 blocking 코드" 가 그대로 고처리량 코드가 된다.**

여기서 이 발표의 절반이 갈린다.

> 코루틴이 필요했던 이유 중 하나였던 **'실행을 값싸게 만들기'** 가
> 언어가 아니라 런타임에서 해결됐다.

### 3. 그런데 안 켜지는 것들 (실측)

`vt` 프로파일에서 v2 를 호출해보면 이렇다.

```
00.131 V[tomcat-handler-2 ] [trace=VT-2] CoreBankAdapter        : [getAccounts] start   ctx=UNKNOWN/VT-2
00.442 P[home-info-1      ] [trace=none] HomeItemInfoRepository : [getHomeItemInfos] start   ctx=없음
00.442 P[open-banking-1   ] [trace=none] OpenBankingAdapter     : [getBalances] start   ctx=없음
00.446 P[user-log-1       ] [trace=none] UserLogRepository      : [saveEvent] start   ctx=없음
```

톰캣만 `V` 다. **내가 직접 만든 `ThreadPoolTaskExecutor` 들은 그대로 플랫폼 스레드다.**

Boot 가 바꿔주는 것은 자기가 만든 것뿐이다 (톰캣, `applicationTaskExecutor`, `taskScheduler`).
직접 정의한 빈은 직접 바꿔야 한다.

> "가상 스레드 켰는데 왜 그대로죠?" 의 90%가 이거다.
> 설정 한 줄로 전부 바뀌지 않는다.

같은 이유로 6단계의 `Dispatchers.IO` 도 여전히 플랫폼 스레드다. → 9·10단계

### 4. 주의할 것들 (실전 팁)

**(a) synchronized 블록에서의 pinning**
JDK 24 (JEP 491) 부터 `synchronized` 안에서 블로킹해도 캐리어 스레드가 고정되지 않는다.
JDK 21 에서는 이게 큰 함정이었다. **JDK 25 를 쓰는 지금은 사실상 해소됐다.**
다만 네이티브 프레임(JNI) 안에서는 여전히 pinning 된다.

**(b) ThreadLocal 이 싸지 않다**
스레드가 수백만 개면 ThreadLocal 도 수백만 벌이다.
가상 스레드에서는 `ScopedValue` 가 권장된다.
3~7단계에서 ThreadLocal 전파에 들인 노력이 여기서 다시 도마에 오른다.

**(c) 스레드 풀이 사라지면 상한도 사라진다**
2단계에서 `AbortPolicy` 로 걸어둔 "오픈뱅킹 동시 호출 30개" 같은 제한이
가상 스레드로 가면 자동으로 없어진다. → 11단계

**(d) DB 커넥션 풀은 그대로다**
가상 스레드 10000개가 커넥션 10개를 기다리는 상황은 개선되지 않는다.
오히려 대기하는 스레드가 늘어 문제가 더 늦게 드러난다.

### 5. 여기서 던져둘 질문

> 실행이 값싸졌다. 그러면 코루틴은 이제 필요 없나?

8단계까지만 보면 그렇게 보인다. 실제로 1단계 코드가 가장 간단하고 가장 빠르다.
**9단계에서 5단계 코드를 같은 프로파일로 돌려서 무엇이 남는지 확인한다.**

## 시연 팁

- 재시작 전후로 **똑같은 curl 을 친다.** 명령이 같다는 게 포인트다.
- 로그의 `P` / `V` 한 글자만 짚는다. (`logback-spring.xml` 의 커스텀 컨버터)
- v1 을 보여준 뒤 바로 v2 를 보여준다. "톰캣만 바뀌었다" 가 즉시 드러난다.

## 다음 단계로

같은 프로파일에서 5·6단계 코드를 돌린다. → [09](09-virtual-thread-coroutine.md)
