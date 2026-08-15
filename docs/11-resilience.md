# 11단계. 안정성 — @ConcurrencyLimit 과 withTimeout

> 2단계에서 답하지 못한 두 질문이 여기서 닫힌다.

## 코드

- `adapter/GuardedOpenBankingAdapter.kt` (`@ConcurrencyLimit`)
- `config/ResilienceConfig.kt` (`@EnableResilientMethods`)
- `service/HomeItemServiceV11.kt` → `GET /api/v11/home/items`

```kotlin
@ConcurrencyLimit(3)
fun getBalances(accountIds: List<AccountId>, failFast: Boolean = false): List<OpenBankBalance>
```

```kotlin
suspend fun getHomeItemsV11(...) = withTimeout(timeoutMillis) {
    coroutineScope { /* 5단계와 같은 구조 */ }
}
```

Spring Framework 7 의 `org.springframework.resilience` 패키지다.
**resilience4j 같은 외부 라이브러리 없이 spring-context 만으로 동작한다.**

## 측정 결과 1 — 묶음 timeout

```bash
curl ".../api/v11/home/items?value=user-1&timeoutMillis=400"
```

```
22.004 V[vt-dispatch-3] [trace=TO-11] CoreBankAdapter        : [getAccounts] start
22.305 V[vt-dispatch-3] [trace=TO-11] CoreBankAdapter        : [getAccounts] end
22.305 P[user-log-v7-4] [trace=TO-11] UserLogRepository      : [saveEvent] start   ← 영향 없음
22.305 V[vt-dispatch-4] [trace=TO-11] HomeItemInfoRepository : [getHomeItemInfos] start
22.305 V[vt-dispatch-5] [trace=TO-11] OpenBankingAdapter     : [getBalances] start
22.406 V[vt-dispatch-4] [trace=TO-11] HomeItemInfoRepository : [getHomeItemInfos] 취소됨(인터럽트)
22.406 V[vt-dispatch-5] [trace=TO-11] OpenBankingAdapter     : [getBalances] 취소됨(인터럽트)
kotlinx.coroutines.TimeoutCancellationException: Timed out waiting for 400 ms
http=500 total=0.41s
```

**두 자식 모두 인터럽트되고 `end` 가 하나도 없다.**

## 측정 결과 2 — 동시 호출 상한

```bash
seq 8 | xargs -P8 -I{} curl -s -o /dev/null ".../api/v11/home/items?value=user-{}"
```

```
36.473  [guarded] 상한(3) 통과   ┐
36.473  [guarded] 상한(3) 통과   │ 3개
36.473  [guarded] 상한(3) 통과   ┘
36.975  [guarded] 상한(3) 통과   ┐
36.975  [guarded] 상한(3) 통과   │ 3개  (+502ms)
36.975  [guarded] 상한(3) 통과   ┘
37.481  [guarded] 상한(3) 통과   ┐
37.481  [guarded] 상한(3) 통과   ┘ 2개  (+506ms)
```

오픈뱅킹이 500ms 이므로 **정확히 500ms 간격으로 3개씩 웨이브가 생긴다.**
상한이 실제로 지켜진다는 걸 시간축으로 보여줄 수 있다.

## 말할 내용

### 1. 2단계 표를 다시 꺼낸다

2단계에서 "오픈뱅킹 동시 호출 30개 제한" 을 스레드 풀로 표현하려 했을 때
선택지가 둘뿐이었고 둘 다 문제가 있었다.

| 정책 | 큐가 찼을 때 | 문제 |
|---|---|---|
| `CallerRunsPolicy` | 호출 스레드가 대신 실행 | 상한이 **새어나간다** (30 + 톰캣 스레드 수) |
| `AbortPolicy` | 예외로 거절 | 상한은 지켜지지만 **버린다** |

그리고 10단계에서 가상 스레드 dispatcher 로 바꾸는 순간 **상한 자체가 사라졌다.**

`@ConcurrencyLimit` 은 동시성 제한을 **실행 자원에서 분리한다.**

| 정책 | 동작 |
|---|---|
| `ThrottlePolicy.BLOCK` (기본) | 자리가 날 때까지 **기다린다**. 안 버린다 |
| `ThrottlePolicy.REJECT` | `InvocationRejectedException` 으로 즉시 거절 |

> 2단계: "새거나(CallerRuns) 버리거나(Abort)"
> 11단계: "기다리거나(BLOCK) 거절하거나(REJECT)"

**그리고 기다리는 쪽이 톰캣 스레드를 태우지 않는다.**
가상 스레드 위에서 기다리는 건 값싸고, 톰캣 스레드는 6단계에서 이미 반납했다.

2단계에서 CallerRuns 가 톰캣 스레드를 붙잡던 것과 정확히 대비된다.
**세 단계의 성과가 여기서 합쳐진다.**

### 2. `withTimeout` 이 4단계 timeout 과 다른 점

4단계에서도 `DeferredResult(3000)` 으로 timeout 을 걸 수 있었다.
하지만 그건 **응답에만** 걸리는 것이었다. 에러를 내려보내도 뒤의
`CompletableFuture` 들은 계속 돌았다. **요청은 실패했는데 부하는 그대로 남는다.**

여기서는 사슬이 끝까지 이어진다.

```
withTimeout 만료
  → coroutineScope 취소
    → 자식 async 들 취소 (구조적 동시성)
      → runInterruptible 이 스레드 인터럽트
        → mock 의 Thread.sleep 이 InterruptedException
```

> 2단계에서 적어둔 "작업 묶음 전체에 거는 timeout 을 표현할 자리가 없다" 가
> 여기서 닫힌다. **그리고 그게 가능한 이유는 5단계에서 확보한 구조적 동시성 때문이다.**

가상 스레드만으로는 이 사슬을 만들 수 없다. 취소를 전파할 부모-자식 관계가 없기 때문이다.

### 3. fire-and-forget 은 timeout 밖에 있다 (의도된 동작)

로그를 보면 `saveEvent` 는 timeout 이 나도 계속 실행된다.
스코프 밖의 executor 로 나갔기 때문이다.

**응답이 timeout 났다고 접속 기록까지 버릴 이유는 없다.**
구조적 동시성에서는 이 "스코프 밖으로 내보냄" 이 코드에 명시되므로
의도인지 실수인지 구분된다.

### 4. 2단계 6개 질문 최종 결산

| # | 2단계의 질문 | 닫힌 단계 |
|---|---|---|
| 1 | 두 작업이 이 요청의 자식인가 | 5 (`coroutineScope`) |
| 2 | 실패 시 형제 취소 | 5 (+ `runInterruptible`) |
| 3 | 클라이언트가 끊으면 | 6 (요청 취소 → 스코프 취소) |
| 4 | 묶음 전체 timeout | **11** (`withTimeout`) |
| 5 | MDC / 컨텍스트 전파 | 3 (수동) → 7 (선언) |
| 6 | 하위 시스템마다 executor | 6 (dispatcher 하나) → 10 (가상 스레드) |
| + | 톰캣 스레드 점유 | 4 (`DeferredResult`) → 6 (`suspend`) |
| + | 하위 시스템 보호 | **11** (`@ConcurrencyLimit`) |

### 5. 조합이 핵심이다

```
가상 스레드      : 실행을 값싸게 만든다        (8~10단계, 런타임)
구조적 동시성    : 수명과 취소를 표현한다      (5단계, 언어)
@ConcurrencyLimit: 하위 시스템을 보호한다      (11단계, 프레임워크)
```

**세 개는 서로를 대체하지 않는다.** 층이 다르다.
"가상 스레드가 코루틴을 대체했는가" 라는 질문이 애초에 층위를 섞은 질문이라는 게
여기서 코드로 드러난다.

### 6. 실전 주의

**(a) 프록시 기반이다**
그래서 `OpenBankingAdapter` 에 직접 붙이지 않고 별도 빈으로 감쌌다.
직접 붙였다면 v1~v10 이 전부 영향을 받아 앞 단계 시연이 달라진다.
self-invocation 함정도 `@Async` / `@Transactional` 과 동일하다.

**(b) BLOCK 은 캐리어 스레드를 먹지 않는가?**
`@ConcurrencyLimit` 은 세마포어 기반이라 가상 스레드에서 대기하면 캐리어가 반납된다.
플랫폼 스레드에서 쓰면 그 스레드가 그대로 묶인다. **어디서 기다리는지가 중요하다.**

**(c) `withTimeout` 은 `runInterruptible` 없이는 반쪽이다**
취소는 전파되지만 blocking 코드는 계속 잔다. 5단계 노트 참고.

**(d) 재시도도 있다**
같은 패키지의 `@Retryable` 을 쓸 수 있다. 다만 timeout·상한과 조합할 때는
"재시도 × 동시 호출" 이 하위 시스템에 몇 배로 가는지 계산해야 한다.

## 시연 팁

- timeout 시연이 제일 강하다. `취소됨(인터럽트)` 두 줄과 `end` 부재를 짚는다.
- 동시 호출 상한은 `xargs -P8` 로 던지고 로그 **타임스탬프**를 보여준다. 500ms 웨이브가 눈에 띈다.
- 2단계 노트의 "코드가 답하지 못하는 것들" 목록을 다시 띄우고 하나씩 지운다.

## 다음 단계로

번외. 코루틴 없이 가상 스레드만으로 여기까지 갈 수 있는가. → [12](12-structured-task-scope.md)
