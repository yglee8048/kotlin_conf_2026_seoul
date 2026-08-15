# 2단계. CompletableFuture 로 병렬, @Async 로 비동기

> 응답 시간은 절반 이하로 줄어든다. 대신 코드가 답하지 못하는 질문이 여섯 개 생긴다.

## 코드

- `controller/HomeItemV2Controller.kt` → `GET /api/v2/home/items`
- `service/HomeItemServiceV2.kt`
- `config/AsyncConfig.kt`
- `adapter/UserLogRepository.saveEventAsync`

1단계 대비 달라지는 건 두 곳뿐이다.

```kotlin
// 병렬: 서로 의존 없는 두 조회
val homeCardInfoFuture = CompletableFuture.supplyAsync(
    { homeItemInfoRepository.getHomeItemInfos(accountIds) },
    homeInfoTaskExecutor,
)
val openBankBalanceFuture = CompletableFuture.supplyAsync(
    { openBankingAdapter.getBalances(openBankAccountIds) },
    openBankingTaskExecutor,
)

// 비동기: 응답에 안 쓰이는 작업
userLogRepository.saveEventAsync(userId, UserEvent.GET_HOME)

val homeItemInfosByAccountId = homeCardInfoFuture.join().associateBy { it.accountId }
val openBankBalancesByAccountId = openBankBalanceFuture.join().associateBy { it.accountId }
```

## 측정 결과

```
V1  1.84s
V2  0.82s
```

```
47.582 P[http-nio-8080-exec-2] CoreBankAdapter        : [getAccounts] start
47.887 P[http-nio-8080-exec-2] CoreBankAdapter        : [getAccounts] end
47.895 P[home-info-1         ] HomeItemInfoRepository : [getHomeItemInfos] start   ┐ 병렬, 서로 다른 풀
47.895 P[open-banking-1      ] OpenBankingAdapter     : [getBalances] start        ┘
47.900 P[user-log-1          ] UserLogRepository      : [saveEvent] start          ← @Async
48.100 P[home-info-1         ] HomeItemInfoRepository : [getHomeItemInfos] end
48.396 P[open-banking-1      ] OpenBankingAdapter     : [getBalances] end
                              ★ 여기서 응답 반환
48.604 P[user-log-1          ] UserLogRepository      : [saveEvent] end            ← 응답 뒤에도 실행 중
```

300 + max(200, 500) = 800ms.

**시연에서 제일 강한 장면**: `saveEvent` 가 응답이 나간 뒤에도 살아있는 마지막 줄.
curl 이 이미 끝났는데 서버 로그가 200ms 뒤에 한 줄 더 찍힌다.

## 말할 내용

### 1. `allOf` 는 필요 없다 (그리고 fail-fast 가 아니다)

두 Future 는 `supplyAsync` 시점에 **이미 실행 중**이다. 각각 `join()` 하는 것만으로 대기 시간은
`max(200ms, 500ms)` 다. `CompletableFuture.allOf(a, b).join()` 은 얻는 게 없다.

이름 때문에 "아무거나 실패하면 즉시 알려준다"고 오해하기 쉬운데 아니다.
`allOf` 는 **전부 완료될 때까지 기다린 뒤** 실패를 알린다
(OpenJDK 구현상 `AndRelay` 가 양쪽 결과를 다 받아야 fire 된다).

실측 (느린 성공 1000ms + 빠른 실패 50ms):

| 방식 | 예외가 던져지는 시점 |
|---|---:|
| `allOf(a, b).join()` | 1007ms |
| `a.join(); b.join()` | 1003ms |
| `b.join(); a.join()` | **55ms** |

성공 경로(200ms + 500ms)는 `allOf` 505ms vs 개별 join 502ms 로 차이 없음.
예외 타입도 양쪽 다 `CompletionException` 으로 동일하다.

> 즉 `allOf` 는 코드만 늘리고, 실패 상황에서는 오히려 느릴 수 있다.

**여기가 5단계로 가는 다리다.** 어느 쪽을 쓰든 공통 문제가 남는다.

> 하나가 실패해도 **나머지 형제 작업은 취소되지 않고 계속 실행된다.**

`coroutineScope { async ... }` 에서는 이게 기본 동작이다. 그 대비를 여기서 예고해둔다.

### 2. Executor 를 왜 3개로 나눴나

| Bean | prefix | core/max | queue | 거부 정책 | 크기 근거 |
|---|---|---:|---:|---|---|
| `homeInfoTaskExecutor` | `home-info-` | 10/10 | 50 | CallerRuns | DB connection pool |
| `openBankingTaskExecutor` | `open-banking-` | 30/30 | 100 | **Abort** | 상대 시스템 처리량 |
| `logTaskExecutor` | `user-log-` | 5/10 | 500 | Discard | 응답 경로 밖 |

하나를 공유하면 느린 하위 시스템 하나가 풀을 다 먹고 나머지의 큐 대기 시간까지 늘린다.
bulkhead 가 없는 상태다.

**핵심은 "크기를 무엇에 맞추는가"** 다. 스레드 수가 아니라 **그 하위 시스템의 실제 capacity** 다.
DB 풀이 10인데 executor 를 50으로 잡으면 connection 대기로 바뀔 뿐이다.

### 3. CallerRuns 와 Abort 는 목적이 정반대다 (헷갈리기 쉬운 지점)

| 정책 | 큐가 찼을 때 | 결과 |
|---|---|---|
| `CallerRunsPolicy` | 호출 스레드가 대신 실행 | 작업이 안 버려짐. 대신 **동시성 상한이 새어나감** |
| `AbortPolicy` | `RejectedExecutionException` | 상한이 실제로 지켜짐. 대신 **거절됨** |

오픈뱅킹처럼 "상대 시스템에 대한 동시 호출 상한" 이 목적이라면 **CallerRuns 는 그 목적을 깨뜨린다.**
동시 호출 수가 30이 아니라 `30 + 지금 밀어넣는 톰캣 스레드 수` 가 되기 때문이다.
게다가 톰캣 스레드를 붙잡는데, 그건 애초에 풀어주려던 대상이다.

개인화 DB 는 다르다. 하드 리밋이 executor 가 아니라 **connection pool** 이라
CallerRuns 로 "순차 실행으로 degrade" 시키는 게 합리적이다.

> 발표 포인트: thread pool 은 애초에 동시성 제한 도구로 쓰기 불편하다.
> 정책이 **새거나(CallerRuns) 버리거나(Abort)** 둘 중 하나다.
> → [11단계](11-resilience.md) `@ConcurrencyLimit` 에서 "기다리거나(BLOCK) 거절하거나(REJECT)" 가 된다.

주의: `CompletableFuture.supplyAsync(supplier, executor)` 는 거절 시
`RejectedExecutionException` 을 **호출 스레드에서 동기로** 던진다. Future 안에 담기지 않는다.

### 4. `@Async` 의 성질 세 가지

`UserLogRepository.saveEventAsync` 에 붙였다. 기존 `saveEvent` 는 동기로 남겨뒀는데,
**1단계가 계속 동기로 동작해야 하기 때문**이다. `saveEvent` 에 직접 붙이면 1단계 데모가 깨진다.

**(a) 프록시 기반이다.**
`saveEventAsync` 안에서 `saveEvent(...)` 를 호출하는 건 self-invocation 이라 프록시를 안 거치고
같은 스레드에서 동기 실행된다. 의도한 동작이지만, 반대로 같은 클래스 안에서 `saveEventAsync` 를
불러도 비동기가 안 걸린다는 뜻이다. `@Transactional` 과 같은 함정이다.

Kotlin 클래스는 기본 final 이라 CGLIB 프록시가 안 되는데, `kotlin("plugin.spring")` 이
`@Component` 붙은 클래스를 열어주기 때문에 동작한다. 이 플러그인이 없으면 조용히 동기 실행된다.

**(b) 반환이 void 라 호출자가 실패를 알 수 없다.**

**(c) 취소 handle 이 없다.** 원 요청이 끊겨도 끝까지 실행된다.

### 5. `@Async` 예외 처리 — 전역 핸들러의 한계

`AsyncConfigurer.getAsyncUncaughtExceptionHandler()` 는 **애플리케이션 전역에 하나뿐**이고,
받을 수 있는 정보도 `Method` 와 파라미터 배열뿐이라 도메인 문맥을 담을 수 없다.

작업별로 다루려면 어떤 선택지가 있는지 실제로 확인해봤다.

| 방법 | 동작 | 비고 |
|---|---|---|
| 메서드 안에서 try/catch | **된다** | 잡으면 전역 핸들러는 안 불림 |
| executor 에 `TaskDecorator` 로 감싸기 | **안 된다** | 아래 참고 |
| `CompletableFuture<Void>` 반환 후 `.exceptionally` | 된다 | 대신 호출자가 처리해야 함 (fire-and-forget 아님) |
| 전역 핸들러에서 method 로 분기 | 된다 | 결국 전역 핸들러 안의 `if` |

**TaskDecorator 가 안 되는 이유**: Spring 의 `AsyncExecutionAspectSupport` 가 executor 에 submit 하는
task **안에서** 이미 예외를 잡아 전역 핸들러로 넘긴다. decorator 의 try/catch 까지 예외가 올라오지 않는다.

실측:

```
[probe-1] PROBE decorator: before
[probe-1] @Async 실행 실패. method=unhandled params=[]     ← 전역 핸들러가 먼저 처리
[probe-1] PROBE decorator: after (예외 못 봄)               ← decorator 는 정상 종료로 봄
```

그래서 이 프로젝트는 **메서드 안에서 잡고**, 전역 핸들러는 누락 방지용 최후 그물로만 둔다.

> 발표 포인트: fire-and-forget 의 실패는 "누가 알게 되는가"를 별도로 설계해야 한다.
> Coroutine 에서는 `CoroutineExceptionHandler` 를 **스코프 단위**로 붙일 수 있어서
> 전역이냐 작업별이냐를 고르지 않아도 된다.

### 6. 그래서 코드가 답하지 못하는 것들

응답 시간은 절반이 됐다. 그런데 코드를 보고 답할 수 없는 질문이 남는다.
**스크립트 슬라이드 5의 질문들을 여기서 실제 코드 위에 얹는다.**

1. 두 Future 가 **현재 HTTP 요청의 자식**이라는 관계가 코드 어디에도 없다. → [5단계](05-coroutine-structured-concurrency.md)
2. 하나가 실패해도 나머지는 취소되지 않는다. → [5단계](05-coroutine-structured-concurrency.md)
3. 클라이언트가 연결을 끊어도 executor 의 작업은 끝까지 돈다. → [6단계](06-suspend-controller.md)
4. **작업 묶음 전체에 거는 timeout** 을 표현할 자리가 없다. (개별 `orTimeout` 은 묶음이 아니다) → [11단계](11-resilience.md)
5. MDC / SecurityContext 가 executor 스레드로 안 넘어간다. → [3단계](03-context-propagation.md) / [7단계](07-context-accessor.md)
6. 하위 시스템이 하나 늘 때마다 Executor 와 그 크기를 다시 고민해야 한다. → [6단계](06-suspend-controller.md) / [10단계](10-virtual-thread-dispatcher.md)

> **이 목록은 발표 전체의 뼈대다.** 11단계에서 하나씩 지워가며 결산한다.

> 공통점: **동시 작업의 관계와 수명이 코드에 표현되어 있지 않다.**
> 두 작업을 시작했다는 사실만 있고, 누가 부모인지·언제까지 살아야 하는지는
> 개발자가 Future 바깥에서 직접 관리해야 한다.

### 7. 그리고 아직 해결 안 된 것

**톰캣 스레드는 여전히 응답까지 붙잡혀 있다.** 800ms 내내 점유 중이다.
1단계에서 1.7초였던 게 0.8초가 됐을 뿐, 점유 구조는 그대로다.

이건 [4단계](04-deferred-result.md)(Deferred 응답)와
[6단계](06-suspend-controller.md)(suspend controller), [8단계](08-virtual-thread.md)(VT)의 주제다.
2단계에서 응답 시간이 절반이 된 걸 보고 "해결됐다"고 넘어가지 않게 여기서 못을 박는다.

## 시연 팁

- 1단계 로그와 나란히 띄운다. **스레드 이름 접두어가 갈라지는 순간**이 시각적으로 가장 강하다.
- curl 이 끝난 뒤 `[saveEvent] end` 가 늦게 찍히는 걸 기다렸다가 보여준다.
- 여유가 있으면 `saveEvent` 에서 예외를 던져 전역 핸들러 로그를 띄운다.
  "호출자는 이걸 전혀 모른다" 를 실물로 보여줄 수 있다.

## 다음 단계로

3단계는 위 목록의 5번(MDC / ThreadLocal 전파)을 다룬다. → [03](03-context-propagation.md)
`home-info-1` 스레드의 로그에 `trace=none` 이 찍히는 걸 먼저 보여주고 시작하면 자연스럽다.
