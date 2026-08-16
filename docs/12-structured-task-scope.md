# 12단계 (번외). StructuredTaskScope — 코루틴 없이 구조적 동시성

> 공정하게 비교하기 위한 단계. "구조적 동시성은 코루틴만의 것" 이 아니라는 걸 먼저 인정한다.

## 코드

- `service/HomeItemServiceV12.kt` → `GET /api/v12/home/items`
- `controller/HomeItemV12Controller.kt` — **평범한 blocking 컨트롤러다**

JDK 25 에서 `StructuredTaskScope` 는 아직 preview 다. (JEP 505, 5차)

```kotlin
// build.gradle.kts
tasks.withType<JavaCompile> { options.compilerArgs.add("--enable-preview") }
tasks.withType<JavaExec> { jvmArgs("--enable-preview") }   // bootRun 포함
tasks.withType<Test> { jvmArgs("--enable-preview") }
```

```kotlin
StructuredTaskScope.open<Any, Void>(
    StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(),
    { config -> config.withName("home-v12").withTimeout(Duration.ofMillis(timeoutMillis)) },
).use { scope ->
    val homeCardInfoTask = scope.fork(Callable { homeItemInfoRepository.getHomeItemInfos(accountIds) })
    val openBankBalanceTask = scope.fork(Callable { openBankingAdapter.getBalances(openBankAccountIds, failFast) })

    scope.join()

    return assemble(accounts, homeCardInfoTask.get(), openBankBalanceTask.get())
}
```

## 코루틴과 거의 1:1로 대응된다

```kotlin
// 5단계
coroutineScope {
    val a = async { infoRepository.get(ids) }
    val b = async { openBanking.get(ids) }
    assemble(a.await(), b.await())
}

// 12단계
StructuredTaskScope.open(...).use { scope ->
    val a = scope.fork(Callable { infoRepository.get(ids) })
    val b = scope.fork(Callable { openBanking.get(ids) })
    scope.join()
    assemble(a.get(), b.get())
}
```

얻는 것도 같다. **부모-자식 관계, 형제 취소, 묶음 timeout, 누수 불가능.**

## 측정 결과

```
### 정상 (vt 프로파일)
03.885 V[tomcat-handler-7] [trace=VT-12] HomeItemV12Controller  : [v12] 진입
03.885 V[tomcat-handler-7] [trace=VT-12] CoreBankAdapter        : [getAccounts] start
04.194 P[user-log-v7-1   ] [trace=VT-12] UserLogRepository      : [saveEvent] start
04.197 V[virtual-73      ] [trace=none ] OpenBankingAdapter     : [getBalances] start   ctx=없음
04.197 V[virtual-72      ] [trace=none ] HomeItemInfoRepository : [getHomeItemInfos] start ctx=없음
04.704 V[tomcat-handler-7] [trace=VT-12] HomeItemV12Controller  : [v12] 반환 (진입과 같은 스레드다)
total = 0.83s
```

```
### 형제 취소 (?failFast=true)
54.493 V[virtual-123] OpenBankingAdapter     : [getBalances] start
54.493 V[virtual-122] HomeItemInfoRepository : [getHomeItemInfos] start
54.548 V[virtual-123] OpenBankingAdapter     : [getBalances] 의도된 실패
54.548 V[virtual-122] HomeItemInfoRepository : [getHomeItemInfos] 취소됨(인터럽트)   ← 취소된다
StructuredTaskScope$FailedException: ... OpenBankingException
```

```
### 묶음 timeout (?timeoutMillis=400)
52.731 V[virtual-120] OpenBankingAdapter : [getBalances] start
53.137 V[virtual-120] OpenBankingAdapter : [getBalances] 취소됨(인터럽트)
StructuredTaskScope$TimeoutException
total = 0.72s
```

**5단계·11단계와 똑같은 결과가 나온다.** 인정하고 시작할 부분이다.

## 말할 내용

### 1. 먼저 인정한다

> 구조적 동시성은 코루틴만의 것이 아니다.

`StructuredTaskScope` 는 코루틴이 제공하던 것 중 **구조화** 부분을 자바 표준으로 가져온다.
`awaitAllSuccessfulOrThrow` 는 형제를 취소하고, `withTimeout` 은 묶음에 걸리고,
`close()` 는 자식이 다 끝나야 반환한다.

여기까지 오면 이 발표의 결론이 흔들려 보인다. 그래서 정면으로 다룬다.

### 2. 그래도 남는 차이

| | 코루틴 | StructuredTaskScope |
|---|---|---|
| 상태 | 정식 | **preview** (JDK 25 기준 5차) |
| 취소 전달 | 협조적 취소 + 인터럽트 | 인터럽트 |
| blocking 래핑 | `runInterruptible` 필요 | 불필요 (원래 blocking 세계) |
| 컨텍스트 전파 | accessor 로 자동 (7단계) | **안 됨** (`ScopedValue` 필요) |
| 컨트롤러 통합 | `suspend` — 반환 타입 그대로 | **톰캣 스레드를 붙잡는다** |
| 스트림 처리 | `Flow`, `Channel` | 없음 |
| 가상 스레드 없이 | 동작함 | 사실상 불가 |

아래 세 줄이 실전에서 크다.

**(a) 톰캣 스레드를 반납하지 않는다 (실측)**
로그의 `[v12] 진입` 과 `[v12] 반환` 이 **같은 스레드**다. `scope.join()` 이 막기 때문이다.
`vt` 프로파일에서는 그게 가상 스레드라 값싸므로 문제되지 않는다.

> 즉 **이 방식은 가상 스레드가 켜져 있다는 전제 위에서만 성립한다.**
> 6단계의 suspend 컨트롤러는 코어뱅킹 조회 이후의 병렬 구간에서 스레드를 반납했다.

플랫폼 스레드 환경(= 아직 JDK 21 미만이거나 VT 를 못 켜는 상황)에서는
12단계 코드가 1단계만큼 스레드를 먹는다.

**(b) 컨텍스트가 안 따라온다 (실측)**
fork 한 스레드 로그가 `[trace=none] ctx=없음` 이다.
가상 스레드에는 ThreadLocal 이 상속되지 않으므로 3–7단계에서 한 작업이 전부 무효다.
제대로 하려면 `ScopedValue` 로 갈아엎어야 한다.
**7단계의 accessor 선언이 여기서는 아무 일도 하지 않는다.**

**(c) preview 다**
`--enable-preview` 로 빌드한 애플리케이션은 프로덕션에 올리기 어렵다.
JDK 마이너 버전이 바뀌면 API 가 바뀔 수 있고, 실제로 JEP 505 는 5차 preview 다.
(`StructuredTaskScope.open()` 시그니처는 4차와 5차 사이에도 바뀌었다)

### 3. Kotlin 에서의 함정

```kotlin
scope.fork { ... }                  // fork(Runnable) 이 선택되어 결과가 null
scope.fork(Callable { ... })        // 이렇게 써야 한다
```

`fork` 에 `Callable` 과 `Runnable` 오버로드가 둘 다 있고, Kotlin 은 `Runnable` 을 고른다.
컴파일도 되고 실행도 되는데 `get()` 이 `null` 을 반환한다.
**타입 시스템이 안 잡아주는 조용한 버그다.**
`src/test/.../PreviewFeatureEnabledTest.kt` 로 이 동작을 고정해뒀다.

### 4. 결론 — 발표의 마지막 슬라이드로

```
Virtual Thread       : 값싼 실행을 준다        → 코루틴이 필요했던 이유 하나를 제거
StructuredTaskScope  : 구조화를 준다           → 또 하나를 제거 (단, preview)
```

두 개가 코루틴의 영역을 상당히 잠식한 것이 맞다. 그런데 **blocking MVC 환경**에서
오늘 당장 쓸 수 있는 것을 기준으로 보면 이렇다.

- **점진적 도입**: `runBlocking` 하나로 시작해 위로 넓힐 수 있다 (5→6단계)
- **컨트롤러 통합**: `suspend` 로 코어뱅킹 이후 병렬 구간의 스레드를 반납한다. VT 없이도 된다
- **컨텍스트 전파**: accessor 선언 하나로 dispatcher·executor 모두 해결 (7단계)
- **정식 API**: preview 플래그 없이 프로덕션에 올릴 수 있다
- **스트림**: `Flow` 는 아직 자바에 대응물이 없다

> **blocking MVC 환경에서도 코루틴은 여전히 유효하다.**
> 코드가 간결해지고, 구조적 동시성을 쉽게 확보할 수 있다.
>
> Virtual Thread 는 코루틴을 대체한 것이 아니라,
> 코루틴이 필요했던 이유 중 하나(값싼 실행)를 제거했다.

## 시연 팁

- 5단계 코드와 12단계 코드를 좌우로 붙인다. 얼마나 닮았는지 보여주는 게 먼저다.
- 그 다음 `[v12] 진입` / `[v12] 반환` 이 같은 스레드인 걸 짚는다. 여기가 갈리는 지점이다.
- `ctx=없음` 을 짚으며 3–7단계를 되짚는다. "그 노력이 여기서는 안 먹힌다."
- `--enable-preview` 없이 실행하면 어떻게 되는지 미리 한 번 보여주는 것도 좋다.

## 마무리

전체 결산은 [README](README.md) 의 진행 상황 표와 관통 메시지를 참고.
실전 팁은 [90](90-tip-async-transaction.md).
