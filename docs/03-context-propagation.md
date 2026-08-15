# 3단계. 커스텀 ThreadLocal 과 MDC 전파하기

> 2단계에서 남긴 여섯 개 중 하나를 해결한다. 그리고 새 부채를 하나 만든다.

## 코드

- `context/CallContext.kt` — traceId / channel / deviceId / userId
- `context/CallContextHolder.kt` — ThreadLocal 홀더
- `context/CallContextFilter.kt` — 요청 진입 시 컨텍스트 + MDC 세팅 (v1·v2·v3 **공통**)
- `context/CallContextTaskDecorator.kt` — 캡처 → 주입 → 원복
- `config/ContextAwareAsyncConfig.kt` — decorator 를 붙인 executor 3개
- `service/HomeItemServiceV3.kt` → `GET /api/v3/home/items`

## 먼저 "안 된다"를 보여준다

필터는 v1·v2·v3 **전부에 똑같이** 걸려 있다. 그래서 세 버전 모두 톰캣 스레드에서는 컨텍스트가 정상이다.
차이는 worker 스레드로 넘어갈 때뿐이다. 이 순서로 보여주는 게 좋다.

### v1 — 문제가 없다

```
P[http-nio-8080-exec-6] [trace=TRACE-V1] CoreBankAdapter        : [getAccounts] start   ctx=WEB/TRACE-V1
P[http-nio-8080-exec-6] [trace=TRACE-V1] HomeItemInfoRepository : [getHomeItemInfos] start   ctx=WEB/TRACE-V1
P[http-nio-8080-exec-6] [trace=TRACE-V1] OpenBankingAdapter     : [getBalances] start   ctx=WEB/TRACE-V1
P[http-nio-8080-exec-6] [trace=TRACE-V1] UserLogRepository      : [saveEvent] start   ctx=WEB/TRACE-V1
```

전부 한 스레드니까 ThreadLocal 이 그냥 동작한다. **1단계에서 인정하고 시작했던 장점이 이것이다.**

### v2 — 톰캣 스레드를 벗어나는 순간 사라진다

```
P[http-nio-8080-exec-2] [trace=TRACE-V2] CoreBankAdapter        : [getAccounts] start   ctx=MOBILE/TRACE-V2
P[http-nio-8080-exec-2] [trace=TRACE-V2] CoreBankAdapter        : [getAccounts] end     ctx=MOBILE/TRACE-V2
P[home-info-1         ] [trace=none]     HomeItemInfoRepository : [getHomeItemInfos] start   ctx=없음
P[open-banking-1      ] [trace=none]     OpenBankingAdapter     : [getBalances] start   ctx=없음
P[user-log-1          ] [trace=none]     UserLogRepository      : [saveEvent] start   ctx=없음
```

**이 대비가 이 단계의 전부다.** 같은 요청인데 로그가 두 세계로 갈라진다.

여기서 강조할 것:

- 장애 조사할 때 traceId 로 grep 하면 **앞부분 두 줄만 나온다.** 정작 느린 구간인
  오픈뱅킹 호출 로그는 검색에 안 걸린다.
- 병렬화해서 얻은 성능을 **관측 가능성으로 지불한 것**이다.
- 그리고 이건 로그만의 문제가 아니다. worker 스레드에서 `CallContextHolder.get()` 이 `null` 이라
  `channel` 로 분기하는 비즈니스 로직이 있으면 **조용히 다르게 동작한다.**

### v3 — 전파된다

```
P[http-nio-8080-exec-4] [trace=TRACE-V3] CoreBankAdapter        : [getAccounts] start   ctx=MOBILE/TRACE-V3
P[home-info-v3-1      ] [trace=TRACE-V3] HomeItemInfoRepository : [getHomeItemInfos] start   ctx=MOBILE/TRACE-V3
P[open-banking-v3-1   ] [trace=TRACE-V3] OpenBankingAdapter     : [getBalances] start   ctx=MOBILE/TRACE-V3
P[user-log-v3-1       ] [trace=TRACE-V3] UserLogRepository      : [saveEvent] start   ctx=MOBILE/TRACE-V3
```

MDC(`trace=`)와 커스텀 ThreadLocal(`ctx=`)이 **둘 다** 따라갔다.
`@Async` 로 던진 로그 적재까지 포함해서다.

응답 시간은 v2 와 동일(0.82s). **전파는 공짜에 가깝다는 것도 같이 말해준다.**

## 말할 내용

### 1. V2 와 V3 서비스 코드는 한 글자도 다르지 않다

`HomeItemServiceV2` 와 `HomeItemServiceV3` 를 나란히 띄우면 diff 가 executor 이름뿐이다.

```kotlin
CompletableFuture.supplyAsync({ ... }, homeInfoTaskExecutor)      // v2
CompletableFuture.supplyAsync({ ... }, homeInfoTaskExecutorV3)    // v3
```

그리고 executor 설정의 diff 는 한 줄이다.

```kotlin
setTaskDecorator(CallContextTaskDecorator())
```

> 전파는 **호출부의 문제가 아니라 실행 경계의 문제**다.
> 실행 경계에서 한 번 해결하면 호출부는 몰라도 된다.

실무라면 executor 를 새로 만들 것 없이 기존 것에 decorator 한 줄 추가하고 끝난다.
여기서 v3 용 executor 를 따로 만든 건 발표에서 v2 와 나란히 비교하기 위해서다.

### 2. decorator 가 executor 에 붙는다는 게 왜 중요한가

`supplyAsync(..., executor)` 든 `@Async("executorName")` 이든, **그 executor 를 쓰는 모든 경로**에
자동으로 적용된다. `saveEventAsyncV3` 는 `saveEventAsync` 와 본문이 완전히 같고 `@Async` 의
executor 이름만 다른데, 그것만으로 전파가 된다.

호출부마다 챙기는 방식이었다면 새 비동기 호출을 추가할 때마다 까먹을 수 있다.

### 3. 세 단계 모델: 캡처 → 주입 → 원복

```kotlin
override fun decorate(runnable: Runnable): Runnable {
    // 1. 캡처 — 여기는 "제출하는" 스레드 (톰캣)
    val capturedContext = CallContextHolder.get()
    val capturedMdc = MDC.getCopyOfContextMap()

    return Runnable {
        // 2. 주입 — 여기는 "실행하는" 스레드 (풀)
        val previousContext = CallContextHolder.get()
        val previousMdc = MDC.getCopyOfContextMap()
        try {
            CallContextHolder.set(capturedContext)
            applyMdc(capturedMdc)
            runnable.run()
        } finally {
            // 3. 원복 — 풀 스레드는 재사용된다
            CallContextHolder.set(previousContext)
            applyMdc(previousMdc)
        }
    }
}
```

**`decorate()` 가 제출 스레드에서 호출된다**는 게 이 구조의 전제다.
`ThreadPoolTaskExecutor.execute()` 가 submit 전에 decorate 를 부르기 때문에 성립한다.

**3번 원복이 빠지면 전파 안 되는 것보다 나쁘다.** 컨텍스트가 풀 스레드에 눌어붙어서,
다음에 그 스레드를 쓰는 다른 요청이 **남의 traceId 와 userId 를 본다.**
없는 건 눈에 띄지만 틀린 건 안 띈다.

원복을 `clear()` 가 아니라 "이전 값으로 되돌리기" 로 한 이유도 있다.
CallerRunsPolicy 를 건 executor 에서는 이 Runnable 이 **톰캣 스레드에서 직접 실행될 수 있다.**
그때 `clear()` 하면 아직 처리 중인 원래 요청의 컨텍스트를 지워버린다.
(2단계에서 개인화 DB executor 에 CallerRuns 를 걸어둔 것이 여기서 실제로 걸린다)

### 4. `InheritableThreadLocal` 은 답이 아니다

나올 만한 질문이라 미리 준비해둔다.

`InheritableThreadLocal` 은 **스레드가 생성되는 시점**에 부모 값을 복사한다.
thread pool 은 스레드를 최초 1회만 만들고 계속 재사용한다. 그래서

- 풀 스레드가 처음 만들어질 때 우연히 있던 컨텍스트가 영원히 남고
- 이후 모든 요청이 그 값을 보게 된다

전파 실패가 아니라 **조용한 오염**이다. pool 환경에서는 쓰면 안 된다.

### 5. 수동 래핑은 왜 안 하나

decorator 없이 호출부에서 직접 할 수도 있다.

```kotlin
val ctx = CallContextHolder.get()
val mdc = MDC.getCopyOfContextMap()
CompletableFuture.supplyAsync({
    val prev = CallContextHolder.get()
    try {
        CallContextHolder.set(ctx)
        MDC.setContextMap(mdc)
        homeItemInfoRepository.getHomeItemInfos(accountIds)
    } finally {
        CallContextHolder.set(prev)
    }
}, executor)
```

문제는 세 가지다.

1. 비동기 호출이 하나 늘 때마다 반복해야 한다. **한 번 까먹으면 그 경로만 조용히 깨진다.**
2. 비즈니스 로직이 안 보인다. 위 코드에서 실제 일은 가운데 한 줄뿐이다.
3. `@Async` 에는 아예 적용할 수 없다. 호출부가 프록시라 감쌀 자리가 없다.

### 6. 그래서 새로 생긴 부채

전파할 항목이 늘 때마다 `CallContextTaskDecorator` 를 고쳐야 한다.

- MDC
- CallContext
- SecurityContext
- 트랜잭션 동기화
- 각종 라이브러리의 ThreadLocal (tracing agent, feature flag SDK, ...)

**"전파해야 할 것들의 목록"을 사람이 관리한다는 게 문제다.** 새 라이브러리를 붙일 때
그 라이브러리가 ThreadLocal 을 쓰는지 확인하고 이 목록에 추가하는 걸 기억해야 한다.

7단계에서 Spring 7 의 context accessor 가 이 목록을 없앤다. 여기서 예고해두면 연결이 자연스럽다.

### 7. 2단계 목록 진행 상황

| # | 남은 문제 | 상태 |
|---|---|---|
| 1 | 두 Future 가 현재 요청의 자식이라는 관계가 코드에 없다 | 남음 |
| 2 | 하나가 실패해도 나머지는 취소되지 않는다 | 남음 |
| 3 | 클라이언트가 끊어도 작업은 끝까지 돈다 | 남음 |
| 4 | 묶음 전체 timeout 을 표현할 자리가 없다 | 남음 |
| 5 | MDC / SecurityContext 가 전파되지 않는다 | **해결 (단, 목록 관리 부채)** |
| 6 | 하위 시스템마다 Executor 크기를 고민해야 한다 | 남음 |
| + | 톰캣 스레드는 여전히 응답까지 붙잡혀 있다 | 남음 |

## 시연 팁

### 호출 명령

```bash
# v2 — worker 스레드에서 컨텍스트 소실
curl -s -o /dev/null \
  -H "X-Channel: MOBILE" -H "X-Device-Id: iPhone15" -H "X-Trace-Id: TRACE-V2" \
  "http://localhost:8080/api/v2/home/items?value=user-1"

# v3 — 전파됨
curl -s -o /dev/null \
  -H "X-Channel: MOBILE" -H "X-Device-Id: iPhone15" -H "X-Trace-Id: TRACE-V3" \
  "http://localhost:8080/api/v3/home/items?value=user-1"
```

traceId 를 헤더로 직접 넣을 수 있게 해뒀다. 발표에서는 `TRACE-V2` / `TRACE-V3` 처럼
눈에 띄는 값을 쓰는 게 랜덤 UUID 보다 낫다. 헤더를 안 주면 8자리 UUID 가 생성된다.

### 로그 grep 시연

말로 설명하는 것보다 이게 강하다.

```bash
grep TRACE-V2 app.log   # 2줄. 정작 느린 구간이 안 나옴
grep TRACE-V3 app.log   # 8줄. 전 구간이 다 나옴
```

### 로그 패턴

`src/main/resources/logback-spring.xml` 에서 MDC 를 노출해뒀다.

```xml
<pattern>%d{HH:mm:ss.SSS} %-5level %vt[%-20.20t] [trace=%-8.8X{traceId:-none}] %-22.22logger{0} : %m%n</pattern>
```

`%X{traceId:-none}` 이라 전파 안 된 스레드는 `trace=none` 으로 찍힌다.
(`%vt` 는 플랫폼/가상 스레드를 `P`/`V` 로 찍는 커스텀 conversionRule 이다. 8단계에서 쓴다)
`ctx=` 는 커스텀 ThreadLocal, `trace=` 는 MDC 다. **둘을 따로 보여주는 게 포인트다.**
MDC 만 챙기면 되는 게 아니라는 걸 눈으로 확인시킬 수 있다.

## 다음 단계로

4단계는 위 표의 마지막 항목(톰캣 스레드 점유)을 다룬다. → [04](04-deferred-result.md)
지금까지 v2·v3 모두 응답 시간 800ms 내내 톰캣 스레드가 붙잡혀 있다는 걸 짚고 넘어가면 자연스럽다.

그리고 여기서 만든 `CallContextTaskDecorator` 는 **7단계에서 통째로 없어진다.**
"전파 대상 목록을 손으로 관리한다" 는 부채를 지금 명시해두면 그때 회수가 깔끔하다.
→ [07](07-context-accessor.md)

트랜잭션도 ThreadLocal 이라 같은 문제를 겪는다. 결과는 훨씬 비싸다.
→ [90](90-tip-async-transaction.md)
