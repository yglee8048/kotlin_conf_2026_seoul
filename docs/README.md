# 단계별 발표 노트

`kotlin-conference-script.md` 가 발표 전체 흐름이라면, 이 폴더는 **데모 코드에 붙는 노트**다.
각 파일은 그 단계에서 실제로 돌려본 결과와, 코드를 보여주며 말할 내용을 담는다.

## 진행 상황

| 단계 | 내용 | 상태 | 노트 |
|---:|---|---|---|
| 1 | blocking MVC 일반적인 홈 화면 조회 | 구현됨 | [01](01-blocking-mvc-sequential.md) |
| 2 | 병렬 호출과 비동기 적용 (CompletableFuture, @Async) | 구현됨 | [02](02-completable-future-async.md) |
| 3 | 커스텀 ThreadLocal / MDC 전파 | 구현됨 | [03](03-context-propagation.md) |
| 4 | controller 에서 Deferred 응답 | 구현됨 | [04](04-deferred-result.md) |
| 5 | Coroutine 적용 (구조적 동시성) | 구현됨 | [05](05-coroutine-structured-concurrency.md) |
| 6 | suspend controller | 구현됨 | [06](06-suspend-controller.md) |
| 7 | Spring 7 accessor 로 context 자동 전파 | 구현됨 | [07](07-context-accessor.md) |
| 8 | Virtual Thread (1단계 코드 재사용) | 구현됨 | [08](08-virtual-thread.md) |
| 9 | Virtual Thread + Coroutine (5단계 코드 재사용) | 구현됨 | [09](09-virtual-thread-coroutine.md) |
| 10 | Virtual Thread dispatcher | 구현됨 | [10](10-virtual-thread-dispatcher.md) |
| 11 | 안정성 (concurrency limit, timeout) | 구현됨 | [11](11-resilience.md) |
| 12 | 번외: Virtual Thread 로 비동기/병렬 직접 구현 | 구현됨 | [12](12-structured-task-scope.md) |
| 팁 | 비동기 + @Transactional 의 위험성 | 구현됨 | [90](90-tip-async-transaction.md) |

> 실전 팁 중 "CoroutineScope 를 Bean 으로" 는 별도 예제 없이
> 5단계 안에서 `ApplicationCoroutineScope` 로 다룬다. → [05](05-coroutine-structured-concurrency.md)

## 실행

```bash
# 1~7단계
./gradlew bootRun

# 8~12단계 (재시작 필요)
./gradlew bootRun --args='--spring.profiles.active=vt'
```

`vt` 프로파일이 하는 일은 `spring.threads.virtual.enabled: true` 한 줄이다.
**애플리케이션 코드는 바뀌지 않는다.** 그게 8단계의 메시지다.

12단계가 JDK 25 preview API(`StructuredTaskScope`)를 쓰므로
빌드와 실행에 `--enable-preview` 가 걸려 있다. (`build.gradle.kts`)

## 데모 시나리오 공통

### mock 지연

발표 내내 같은 API 하나(`홈 화면 조회`)를 발전시킨다. 각 하위 시스템은 고정 지연을 갖는다.

| 호출 | 지연 | 성격 |
|---|---:|---|
| `CoreBankAdapter.getAccounts` | 300ms | 코어뱅킹. 이후 두 조회의 **입력**이라 병렬화 불가 |
| `HomeItemInfoRepository.getHomeItemInfos` | 200ms | 개인화 DB |
| `OpenBankingAdapter.getBalances` | 500ms | 외부 HTTP. 가장 느림 |
| `UserLogRepository.saveEvent` | 700ms | 접속 기록. **응답에 안 쓰임** |

지연 값은 각 클래스의 `LATENCY_MILLIS` 상수다.

숫자를 이렇게 고른 이유:

- `getAccounts` 가 앞에 오는 의존이 있어서, "전부 병렬"이 아니라 "**의존 관계를 봐야 한다**"는 걸 보여준다.
- `saveEvent(700ms)` 가 가장 느리다. 응답에 필요 없는 작업이 응답 시간을 지배하는 상황을 만든다.
- `saveEvent(700ms) > getBalances(500ms)` 라서, fire-and-forget 하면 **응답이 나간 뒤에도 로그 작업이 살아있는 것**이 로그에 남는다.

mock 의 `Thread.sleep` 은 `utils.mockLatency` 를 거친다.
인터럽트되면 `취소됨(인터럽트)` 로그를 남기고 다시 던지므로,
**5단계 이후 취소가 실제로 먹혔는지를 로그로 확인할 수 있다.**

### 계좌 더미 데이터

`getAccounts` 는 항상 3건을 반환한다.

| 계좌 | 타입 | 잔액 출처 | 개인화 정보 |
|---|---|---|---|
| 110-1234-5678 | DEPOSIT | 코어뱅킹 응답에 포함 | 있음 (생활비 통장 / mint) |
| 333-9876-5432 | OPEN_BANK | 오픈뱅킹 조회 필요 | 있음 (비상금 통장 / coral) |
| 777-1111-2222 | OPEN_BANK | 오픈뱅킹 조회 필요 | **없음 → 기본값 fallback** |

세 번째 계좌는 일부러 개인화 정보를 비웠다. 응답에서 `alias: "오픈뱅킹"`, `color: default` 로 나오는 걸로
"모든 계좌에 개인화 행이 있는 게 아니다" 를 보여준다.

### 호출 방법

`userId` 는 `UserId` data class 바인딩이라 쿼리 파라미터 이름이 `value` 다.

```bash
for v in 1 2 3 4 5 6 7 10 11 12; do
  curl -s -o /dev/null -w "v$v total=%{time_total}s\n" \
    "http://localhost:8080/api/v$v/home/items?value=user-1"
done
```

3단계부터는 호출 컨텍스트를 헤더로 넣을 수 있다. 안 주면 traceId 는 자동 생성, 채널은 `UNKNOWN`.

```bash
curl -s -o /dev/null \
  -H "X-Trace-Id: TRACE-V3" -H "X-Channel: MOBILE" -H "X-Device-Id: iPhone15" \
  "http://localhost:8080/api/v3/home/items?value=user-1"
```

4단계부터는 장애 주입과 timeout 을 붙일 수 있다.

```bash
# 오픈뱅킹을 50ms 만에 실패시킨다 (형제 작업이 아직 도는 시점)
curl ".../api/v4/home/items?value=user-1&failFast=true"   # 형제가 안 죽는다
curl ".../api/v6/home/items?value=user-1&failFast=true"   # 형제가 죽는다

# 묶음 timeout (11·12단계)
curl ".../api/v11/home/items?value=user-1&timeoutMillis=400"

# 동시 호출 상한 (11단계, 상한 3)
seq 8 | xargs -P8 -I{} curl -s -o /dev/null ".../api/v11/home/items?value=user-{}"
```

### 시연 전용 엔드포인트

```bash
# 7단계. accessor 등록을 런타임에 껐다 켠다 (재시작 없이 before/after)
#
# 5·6단계를 시연할 때는 꺼두는 것을 권한다. 그래야 7단계에서 켜는 순간의 대비가 산다.
# (accessor 가 켜져 있으면 suspend 컨트롤러인 v6 부터 이미 전파된다)
curl "localhost:8080/api/demo/context-accessors"
curl -X POST "localhost:8080/api/demo/context-accessors?enabled=false"

# 실전 팁. 비동기 + @Transactional
curl -X POST "localhost:8080/api/tips/point/charge?userId=user-1&amount=1000&mode=async&fail=true"
curl "localhost:8080/api/tips/point?userId=user-1"
```

### 관찰 포인트

모든 mock 은 진입/종료 시 로그를 남긴다. 발표 중에는 이 로그를 띄워두는 것이 핵심이다.

```
22:41:03.512 INFO  P[open-banking-1     ] [trace=none    ] OpenBankingAdapter : [getBalances] start   size=2 ctx=없음
                   ^ ^                     ^
                   | |                     +- MDC 전파 여부 (3·7단계)
                   | +- 어느 풀에서 도는가 (2단계 이후)
                   +- 플랫폼(P) 인가 가상(V) 스레드인가 (8단계 이후)
```

로그 패턴은 `src/main/resources/logback-spring.xml` 에 있다.
`P`/`V` 는 커스텀 conversionRule(`logging/ThreadKindConverter.kt`)이다.

| 접두어 | 의미 | 등장 |
|---|---|---|
| `http-nio-8080-exec-N` | 톰캣 워커 (플랫폼) | 1단계~ |
| `tomcat-handler-N` | 톰캣 워커 (가상) | `vt` 프로파일 |
| `home-info-N` / `open-banking-N` / `user-log-N` | 2단계 executor | 2단계 |
| `*-v3-N` | 3단계 executor (`CallContextTaskDecorator`) | 3·4단계 |
| `DefaultDispatcher-worker-N` | `Dispatchers.IO` (플랫폼, 기본 64개) | 5·6·7단계 |
| 톰캣 워커 (v5 이후 `getAccounts`) | 코어뱅킹은 모든 단계에서 톰캣 스레드에서 blocking | 5~12단계 |
| `user-log-v7-N` | 7단계 executor (`ContextPropagatingTaskDecorator`) | 7단계~ |
| `vt-dispatch-N` | 가상 스레드 dispatcher | 10·11단계 |
| `virtual-N` | `StructuredTaskScope` 가 만든 가상 스레드 | 12단계 |

### 응답 시간 요약 (실측)

| 엔드포인트 | 응답 | 톰캣 스레드 점유 | 비고 |
|---|---:|---:|---|
| v1 | 1.84s | 1840ms | 순차 |
| v2 / v3 | 0.83s | 830ms | 병렬 + fire-and-forget |
| v4 | 0.87s | **320ms** | DeferredResult. 코어뱅킹은 blocking 유지, `join()` 대기만 제거 |
| v5 | 1.00s | 828ms | 코루틴. `runBlocking` 이 **서비스 안 병렬 구간만** 감쌈 |
| v6 / v7 | 0.85s | **320ms** | suspend 컨트롤러. 코어뱅킹은 blocking 유지 |
| v10 / v11 | 0.83s | 320ms | 코어뱅킹은 톰캣 VT, 병렬 조회는 가상 스레드 dispatcher |
| v12 | 0.83s | 830ms | StructuredTaskScope (`join` 이 막음) |

응답 시간은 4단계 이후 거의 변하지 않는다.
**4단계 이후의 성과는 지연이 아니라 처리량이다.** 이 구분을 계속 강조한다.

## 전체 관통 메시지

README 의 결론을 다시 적어둔다. 각 단계는 이 문장으로 수렴해야 한다.

> blocking MVC 환경에서도 코루틴은 여전히 유효하다. 코드가 간결해지고, 구조적 동시성을 쉽게 확보할 수 있다.

Virtual Thread 는 코루틴을 대체한 게 아니라, **코루틴이 필요했던 이유 중 하나(값싼 실행)** 를 제거했다.
남은 이유(수명·실패·취소의 구조화)는 그대로다.

12단계의 `StructuredTaskScope` 는 그 "남은 이유" 마저 자바로 가져오려는 시도지만,
아직 preview 이고 컨텍스트 전파와 프레임워크 통합에서 코루틴이 앞선다.
