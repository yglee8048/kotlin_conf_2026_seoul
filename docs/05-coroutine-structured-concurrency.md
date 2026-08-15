# 5단계. 코루틴 — 병렬이 필요한 구간만 감싼다

> 이 발표의 중심 단계. 2단계에서 던져둔 여섯 개의 질문에 문법으로 답한다.
> 그리고 **시그니처를 하나도 안 바꾸고** 그렇게 한다.

## 코드

- `controller/HomeItemV5Controller.kt` → `GET /api/v5/home/items`
- `service/HomeItemServiceV5.kt`
- `coroutine/ApplicationCoroutineScope.kt` (실전 팁: CoroutineScope 를 Bean 으로)
- `coroutine/BlockingIo.kt` (`runInterruptible`)

**컨트롤러는 1단계와 완전히 같다.** `runBlocking` 은 서비스 안쪽, 병렬 구간에만 있다.

```kotlin
fun getHomeItemsV5(userId: UserId, failFast: Boolean = false): List<HomeItem> {
    // 1단계와 똑같은 평범한 blocking 호출. 뒤의 두 조회가 여기 의존하므로 병렬화 대상이 아니다.
    val accounts = coreBankAdapter.getAccounts(userId)
    if (accounts.isEmpty()) return emptyList()

    // fire-and-forget. launch 는 suspend 함수가 아니라 blocking 코드에서 그냥 호출된다.
    applicationCoroutineScope.launch(CoroutineName("save-event")) {
        blockingIo { userLogRepository.saveEvent(userId, UserEvent.GET_HOME) }
    }

    // 병렬이 필요한 구간만 코루틴으로 들어갔다 나온다
    val (homeCardInfos, openBankBalances) = runBlocking {
        val homeCardInfoDeferred = async(CoroutineName("home-info")) {
            blockingIo { homeItemInfoRepository.getHomeItemInfos(accountIds) }
        }
        val openBankBalanceDeferred = async(CoroutineName("open-banking")) {
            blockingIo { openBankingAdapter.getBalances(openBankAccountIds, failFast) }
        }
        homeCardInfoDeferred.await() to openBankBalanceDeferred.await()
    }

    return accounts.map { /* 조립 — 1단계와 같은 자리 */ }
}
```

## 측정 결과

```
V4  0.87s   톰캣 스레드 점유 320ms
V5  1.00s   톰캣 스레드 점유 828ms   ← 오히려 나빠졌다
```

```
03.781 P[http-nio-8080-exec-1] [trace=T5] HomeItemV5Controller   : [v5] 진입
03.781 P[http-nio-8080-exec-1] [trace=T5] CoreBankAdapter        : [getAccounts] start   ← 톰캣 스레드
04.087 P[http-nio-8080-exec-1] [trace=T5] CoreBankAdapter        : [getAccounts] end
04.100 P[tDispatcher-worker-1] [trace=none] UserLogRepository      : [saveEvent] start    ← app-scope
04.101 P[tDispatcher-worker-3] [trace=none] HomeItemInfoRepository : [getHomeItemInfos] start ┐ 병렬
04.102 P[tDispatcher-worker-2] [trace=none] OpenBankingAdapter     : [getBalances] start      ┘
04.306 P[tDispatcher-worker-3] [trace=none] HomeItemInfoRepository : [getHomeItemInfos] end
04.607 P[tDispatcher-worker-2] [trace=none] OpenBankingAdapter     : [getBalances] end
04.609 P[http-nio-8080-exec-1] [trace=T5] HomeItemV5Controller   : [v5] 반환 (여기까지 톰캣 스레드를 붙잡고 있었다)
04.805 P[tDispatcher-worker-1] [trace=none] UserLogRepository      : [saveEvent] end     ← 응답 뒤에도 살아있다
```

**관찰 포인트 두 개.**

1. `getAccounts` 가 `http-nio-8080-exec-1` 에서 돈다. **코루틴 밖이라 톰캣 스레드 그대로다.**
   병렬 구간만 `DefaultDispatcher-worker-N` 으로 넘어간다. 경계가 로그에 그대로 보인다.
2. `[v5] 진입` 과 `[v5] 반환` 이 **같은 스레드**다. `runBlocking` 이 막고 있다.

스레드 점유가 4단계보다 나쁜 건 일부러다. 5단계의 성과는 스레드가 아니라 **코드 구조**이고,
스레드는 6단계에서 `runBlocking` 을 걷어내면 따라온다.
이 순서로 보여줘야 "코루틴 = 논블로킹" 이라는 오해를 깰 수 있다.

## 말할 내용

### 1. 시그니처가 하나도 안 바뀐다는 것부터

4단계는 톰캣 스레드를 반납하는 대가로 **컨트롤러 반환 타입까지** `DeferredResult<List<HomeItem>>`
로 바꿔야 했다. 호출하는 쪽, 테스트, 예외 처리가 전부 영향을 받는다.

여기서는 `List<HomeItem>` 그대로다. **바뀐 파일이 서비스 하나다.**

> 실무에서 기존 blocking MVC 코드베이스에 코루틴을 처음 들일 때 딱 이 모양이 된다.
> 전체를 바꾸지 않아도, 병렬이 필요한 그 블록만 감싸면 오늘 도입할 수 있다.

WebFlux 처럼 전체 스택을 갈아엎어야 하는 전환이 아니라는 것이
이 발표 제목("blocking MVC 에서 코루틴이 여전히 유효한가")에 대한 가장 실용적인 답이다.

### 2. 비교는 3단계와 한다 (4단계가 아니라)

5단계는 톰캣 스레드를 828ms 붙잡는다. 스레드를 반납하는 4단계와 붙이면 불공정한 비교다.
같은 스레드 프로파일(병렬 + 점유 830ms)인 **3단계**와 붙여야 코드 구조의 차이만 남는다.

| | 3단계 (Future) | 5단계 (Coroutine) |
|---|---|---|
| 응답 · 톰캣 점유 | 0.83s · 830ms | 0.85s · 828ms — 같다 |
| 병렬 구간 코드 | `supplyAsync` + executor 지정 + `join` | `async` + `await` |
| 하위 시스템마다 executor | 필요 — 크기·거부 정책 고민 | 불필요 — dispatcher 하나 |
| 실패 시 형제 취소 | 안 됨 | 취소됨 |
| 예외 | `CompletionException` 으로 감싸짐 | 원래 예외 그대로 |
| 작업 누수 | `join()` 빼먹으면 샌다 | 문법적으로 불가능 |
| ThreadLocal 전파 | decorator 로 해결해둠 | **다시 잃었다** → 7단계 |

**4단계(DeferredResult)와의 비교는 6단계를 구현한 뒤에 한다.**
둘 다 톰캣 스레드를 반납하는 시점이라야 공정한 비교가 된다. → [06](06-suspend-controller.md)

`await()` 는 값을 꺼내는 것처럼 **보이지만** 스레드를 막지 않는다.
그게 코루틴이 파는 것의 전부다: **비동기 코드를 동기 코드처럼 쓰게 해준다.**

### 3. `runBlocking` 은 그 자체로 스코프다

`coroutineScope { }` 를 안에 한 겹 더 쓸 필요가 없다.
`runBlocking` 이 만드는 코루틴은 일반 Job 을 가지므로 구조적 동시성이 그대로 성립한다.

| 2단계의 질문 | 5단계에서는 |
|---|---|
| 1. 두 작업이 한 묶음인가? | `runBlocking` 블록 안에 있으면 그렇다 |
| 2. 하나가 실패하면 나머지는? | **취소된다** (아래 실측) |
| 3. 클라이언트가 끊으면? | 6단계에서 요청 취소가 스코프 취소로 이어진다 |
| 4. 묶음 전체 timeout 은? | `withTimeout { }` (11단계) |
| 5. MDC / 컨텍스트 전파 | ✗ 아직 안 됨 (7단계) |
| 6. 하위 시스템마다 executor | **사라졌다** — dispatcher 하나면 된다 |

그리고 표에 없는 것이 하나 더 있다.

> **블록을 빠져나오면 자식이 하나도 안 남아 있음이 보장된다.**
> 즉 작업 누수가 문법적으로 불가능하다.

`CompletableFuture` 에서는 `join()` 을 빼먹으면 그냥 샌다. 컴파일도 되고 테스트도 통과한다.

### 4. 실패 시 형제 취소 (실측)

`?failFast=true` — 4단계와 **같은 명령**을 친다.

```
### 4단계 (CompletableFuture)
03.715 P[home-info-v3-1   ] [getHomeItemInfos] start
03.770 P[open-banking-v3-1] [getBalances] 의도된 실패
03.920 P[home-info-v3-1   ] [getHomeItemInfos] end                ← 끝까지 돈다 (205ms)
http=500 total=0.52s

### 5단계 (runBlocking + async)
35.288 P[tDispatcher-worker-1] [getHomeItemInfos] start
35.341 P[tDispatcher-worker-3] [getBalances] 의도된 실패
35.342 P[tDispatcher-worker-1] [getHomeItemInfos] 취소됨(인터럽트)   ← 1ms 만에 멈춘다
http=500 total=0.37s
```

`end` 가 없다는 것이 증거다. **이미 실패가 확정된 요청을 위해 200ms 를 더 쓰지 않는다.**
DB 커넥션도 그만큼 일찍 놓는다. 응답 시간도 0.52s → 0.37s.

예외도 `CompletionException` 없이 `OpenBankingException` 이 그대로 올라온다.

### 5. `runInterruptible` — 취소는 공짜가 아니다

**이게 실전에서 제일 많이 놓치는 부분이다.**

코루틴 취소는 협조적이다. 취소되면 다음 suspension point 에서 `CancellationException`
이 던져질 뿐이고, `Thread.sleep()` 이나 JDBC 호출은 suspension point 가 없으므로
**취소를 알아채지 못한다.**

```kotlin
withContext(Dispatchers.IO) { Thread.sleep(5000) }      // 취소해도 5초를 다 채운다
runInterruptible(Dispatchers.IO) { Thread.sleep(5000) } // 취소되면 즉시 인터럽트
```

`runInterruptible` 은 취소 시 실행 스레드를 **인터럽트**한다.
위의 `취소됨(인터럽트)` 로그는 이것 없이는 절대 안 찍힌다.

> 구조적 동시성이 취소를 **전파**해도, blocking 코드가 인터럽트를 존중하지 않으면
> 스레드는 계속 자고 있다. **취소 가능성은 하위 계층까지 이어져야 하는 계약이다.**

라이브러리가 `InterruptedException` 을 삼키면 여기서도 방법이 없다.
자체 어댑터를 만들 때는 인터럽트를 삼키지 말 것.

### 5-1. 그런데 실전 스택은 인터럽트에 반응하나 (실측)

응답을 주지 않는 서버에 붙여놓고 300ms 뒤 인터럽트를 걸어봤다.

| blocked 지점 | 인터럽트에 | 실측 |
|---|---|---|
| Thread.sleep / 풀 대기 / Future.get | 즉시 끊김 | 이 데모의 mock 이 이 부류 |
| 소켓 read (플랫폼 스레드) | **무시** | 인터럽트 1초 뒤에도 여전히 blocked. JDBC 드라이버 대부분·Apache HttpClient 가 여기 |
| JDBC 쿼리 (Spring Data JDBC 포함) | **무시** | 끊으려면 `Statement.cancel()` / 쿼리 타임아웃 |
| JDK HttpClient sync send (RestClient 기본 폴백) | 반응 | 12ms 만에 `InterruptedException` (내부가 async) |
| 소켓 read (가상 스레드) | 반응 | 5ms 만에 `SocketException` (JEP 444) → 8~10단계 복선 |

mock 이 `Thread.sleep` 이라 이 데모의 취소가 유난히 깔끔한 것이다.
실전 JDBC·클래식 HTTP 클라이언트에서는 **진행 중인 I/O 가 끝나야 취소가 완료된다.**
취소 지연이 정말 SLA 라면 non-blocking 클라이언트(WebClient)가 답이다.

그래도 남는 것 — 취소된 스코프는 **아직 시작 안 한 후속 작업을 시작하지 않고**,
빠져나올 때 자식이 정리됐음은 보장된다.
진행 중인 I/O 하나가 끝까지 도는 것과 요청 전체가 끝까지 도는 것은 다르다.

### 6. 실전 팁 — CoroutineScope 를 Bean 으로

접속 기록 적재는 **응답보다 오래 살아야 하는 작업**이다.
`runBlocking` 안에서 `launch` 하면 구조적 동시성이 그걸 기다려서 **응답이 700ms 늦어진다.**

그래서 부모를 명시적으로 갈아탄다. `GlobalScope` 대신 Bean 을 쓰는 이유가 셋 있다.

```kotlin
@Component
class ApplicationCoroutineScope : CoroutineScope {
    private val supervisorJob = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { context, throwable -> ... }

    override val coroutineContext =
        supervisorJob + Dispatchers.IO + CoroutineName("app-scope") + exceptionHandler

    @PreDestroy
    fun shutdown() { runBlocking { withTimeoutOrNull(5_000) { supervisorJob.cancelAndJoin() } } }
}
```

**(a) SupervisorJob — API 간 취소 격리**
일반 `Job` 이면 접속 기록 적재 하나가 실패했을 때 이 스코프에 붙은
**다른 요청의 코루틴까지 전부 취소된다.** 반드시 SupervisorJob 이어야 한다.

**(b) CoroutineExceptionHandler — 스코프 단위 예외 처리**
2단계에서 `@Async` 의 한계로 지적한 부분이다. `AsyncConfigurer` 의 핸들러는
애플리케이션 전역에 딱 하나였고 `Method` 와 파라미터 배열밖에 못 받았다.
여기서는 스코프마다 붙일 수 있다. **"전역이냐 작업별이냐"를 고를 필요가 없다.**

**(c) @PreDestroy — 종료 시 취소 전파**
`setWaitForTasksToCompleteOnShutdown(true)` 는 "기다린다" 밖에 못 한다.
여기서는 **취소를 보내고** 정리될 때까지만 기다린다.

> 그리고 `launch` 가 `runBlocking` 블록 **밖에** 있다는 게 코드에 그대로 보인다.
> "실수로 새는 작업" 과 "의도적으로 내보내는 작업" 이 구분된다.
> `CompletableFuture` 에서는 둘 다 똑같이 생겼다.

### 7. 아직 안 되는 것 — 컨텍스트 전파

로그를 보면 코루틴 안의 worker 스레드가 전부 `[trace=none] ctx=없음` 이다.
반면 `getAccounts` 는 톰캣 스레드라 `ctx=UNKNOWN/T5` 가 살아있다.
**경계를 넘는 순간 잃는다는 게 로그 한 화면에 같이 보인다.**

3단계에서 executor 에 걸어둔 decorator 는 코루틴 dispatcher 에는 걸려 있지 않다.
**3단계에서 해결했다고 생각한 문제가 도구를 바꾸자 되돌아왔다.** → 7단계

> 참고: `runBlocking` 은 7단계의 자동 전파가 켜져 있어도 여전히 전파되지 않는다.
> `CoroutinesUtils` 를 거치지 않아 `PropagationContextElement` 가 안 붙기 때문이다.
> 6단계(suspend)부터 전파된다. 자세한 건 [07](07-context-accessor.md) 참고.

## 시연 팁

- **3단계 코드와 5단계 코드를 좌우로 붙인 비교가 5단계의 핵심 한 장이다.**
  (4단계와의 비교는 6단계 뒤에서 — 둘 다 스레드를 반납할 때 붙여야 공정하다)
- 5·6단계를 보여주기 전에 accessor 를 꺼두면 7단계 전후 대비가 선명해진다.
  ```bash
  curl -X POST "localhost:8080/api/demo/context-accessors?enabled=false"
  ```
- `?failFast=true` 를 4·5단계에서 각각 쳐서 `end` 유무를 비교한다.
- 로그에서 `getAccounts` 만 톰캣 스레드인 것을 짚는다. 코루틴 경계가 눈에 보인다.
- `runInterruptible` 을 `withContext` 로 바꾼 버전을 미리 준비해두면
  "취소는 되는데 스레드는 안 멈추는" 상태를 실물로 보여줄 수 있다. (질문 나올 확률 높음)

## 다음 단계로

`runBlocking` 을 걷어내고 `suspend` 로 넓히면 톰캣 스레드가 반납된다. → [06](06-suspend-controller.md)
