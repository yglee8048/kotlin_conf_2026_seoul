# 4단계. DeferredResult 로 톰캣 스레드 반납

> 응답 시간은 그대로다. 대신 톰캣 스레드의 `join()` 대기가 사라지고, 코드는 읽기 어려워진다.

## 코드

- `controller/HomeItemV4Controller.kt` → `GET /api/v4/home/items`
- `service/HomeItemServiceV4.kt`

3단계까지 남아 있던 낭비를 줄인다.

> 병렬로 만들어도 톰캣 스레드는 `join()` 으로 결과를 기다리며 응답까지 붙잡혀 있었다.

```kotlin
// 서비스가 값이 아니라 CompletableFuture 를 반환한다
fun getHomeItemsV4(userId: UserId, failFast: Boolean = false): CompletableFuture<List<HomeItem>> {
    // 코어뱅킹 조회는 blocking 그대로 — 뒤의 두 조회가 이 결과에 의존하므로 어차피 기다려야 한다
    val accounts = coreBankAdapter.getAccounts(userId)
    return composeRest(userId, accounts, failFast)   // join() 없이 thenCombine 체인
}

// 컨트롤러는 DeferredResult 를 반환하고 즉시 끝난다
val deferredResult = DeferredResult<List<HomeItem>>(3_000)
service.getHomeItemsV4(userId).whenComplete { result, error -> ... }
return deferredResult
```

## 측정 결과

```
V3  0.83s   톰캣 스레드 점유 830ms
V4  0.87s   톰캣 스레드 점유 320ms  (= 코어뱅킹 조회 구간만)
```

```
24.928 P[http-nio-8080-exec-9] [trace=TR-V4] HomeItemV4Controller   : [v4] 진입
24.929 P[http-nio-8080-exec-9] [trace=TR-V4] CoreBankAdapter        : [getAccounts] start   ← 톰캣 스레드
25.232 P[http-nio-8080-exec-9] [trace=TR-V4] CoreBankAdapter        : [getAccounts] end
25.237 P[home-info-v3-2      ] [trace=TR-V4] HomeItemInfoRepository : [getHomeItemInfos] start   ┐ 병렬
25.247 P[open-banking-v3-2   ] [trace=TR-V4] OpenBankingAdapter     : [getBalances] start        ┘
25.248 P[user-log-v3-2       ] [trace=TR-V4] UserLogRepository      : [saveEvent] start
25.249 P[http-nio-8080-exec-9] [trace=TR-V4] HomeItemV4Controller   : [v4] 반환 (톰캣 스레드 반납)   ← 321ms
25.443 P[home-info-v3-2      ] [trace=TR-V4] HomeItemInfoRepository : [getHomeItemInfos] end
25.751 P[open-banking-v3-2   ] [trace=TR-V4] OpenBankingAdapter     : [getBalances] end
                                            ★ 여기서 응답 반환 (톰캣 스레드는 이미 딴 일 하는 중)
25.955 P[user-log-v3-2       ] [trace=TR-V4] UserLogRepository      : [saveEvent] end
```

**시연 포인트**: `[v4] 반환` 이 병렬 조회 **start 직후**에 찍히는 것.
2·3단계는 `getBalances` 가 **끝날 때까지**(830ms) 이 자리를 붙잡고 있었다.
500ms 짜리 `join()` 대기가 사라지고, 남은 점유는 코어뱅킹 조회 320ms 뿐이다.

## 말할 내용

### 1. 코어뱅킹 조회는 왜 blocking 으로 남겼나 (질문 나올 지점)

코어뱅킹 조회까지 executor 로 넘기면 점유를 약 0ms 로 만들 수도 있다. 안 한 이유:

- 대기가 톰캣 스레드에서 executor 스레드로 **자리만 옮겨갈 뿐** thread·ms 총량은 같다
- 스레드 hop 비용이 추가되고, 하위 시스템마다 executor 를 만들고 크기를 고민해야 한다
- 이득은 과부하 시 "풀 한도를 넘는 대기가 스레드가 아니라 큐 항목으로 표현된다" 는 것뿐

평시 실익이 없는 복잡도라 감수하지 않았다. 반면 `join()` 대기는 성격이 다르다 —
**이미 다른 스레드가 하고 있는 일을 앉아서 기다리는 순수 낭비**라 콜백으로 없앨 가치가 있다.

> 6단계도 동일한 비교를 위해 코어뱅킹은 blocking 으로 남겨 320ms 점유를 유지한다.
> 차이는 나머지 병렬 구간을 콜백 대신 위에서 아래로 읽히는 코드로 표현한다는 점이다.

### 2. 얻은 것과 잃은 것

| | 3단계 | 4단계 |
|---|---|---|
| 응답 시간 | 0.83s | 0.87s |
| 톰캣 스레드 점유 | 830ms | **320ms** |
| 코드가 위에서 아래로 읽힘 | O | **X** |
| 조립 로직 위치 | 마지막 return 문 | 콜백 안 |

**4단계는 응답 시간을 줄이는 게 아니라 처리량을 늘리는 최적화다.**
이 구분을 명확히 해야 한다. Little's Law 로 돌아가서,
같은 톰캣 풀로 감당할 수 있는 동시 요청이 점유 시간에 반비례해 2.6배로 늘어난다.

### 3. `DeferredResult` 의 timeout 은 작업을 취소하지 않는다

```kotlin
deferredResult.onTimeout {
    log.warn("[v4] 응답 timeout. 단, 뒤의 작업들은 계속 실행 중이다.")
    deferredResult.setErrorResult(IllegalStateException("timeout"))
}
```

이건 **응답에만** 걸리는 timeout 이다. 클라이언트에게 에러를 내려보내도
`CompletableFuture` 들은 계속 돈다. 요청은 실패했는데 부하는 그대로 남는다.

> 2단계에서 적어둔 "작업 묶음 전체에 거는 timeout 을 표현할 자리가 없다" 는
> 4단계에서도 해결되지 않는다. `DeferredResult(3000)` 은 그럴듯해 보이지만 다른 것이다.

이건 11단계 `withTimeout` 과 나란히 놓고 비교하면 명확해진다.

### 4. 실패해도 형제는 취소되지 않는다 (실측)

`?failFast=true` 로 오픈뱅킹을 50ms 만에 실패시킨다.

```
03.715 P[home-info-v3-1   ] HomeItemInfoRepository : [getHomeItemInfos] start
03.715 P[open-banking-v3-1] OpenBankingAdapter     : [getBalances] start
03.770 P[open-banking-v3-1] OpenBankingAdapter     : [getBalances] 의도된 실패      ← 55ms
03.920 P[home-info-v3-1   ] HomeItemInfoRepository : [getHomeItemInfos] end        ← 끝까지 돈다
```

`end` 가 찍힌다는 게 핵심이다. **이미 실패가 확정된 요청을 위해 200ms 를 더 쓴다.**
DB 커넥션도 그동안 잡고 있다.

이 로그는 6단계와 나란히 띄워야 한다. 거기서는 같은 자리에
`[getHomeItemInfos] 취소됨(인터럽트)` 이 찍히고 `end` 는 없다.

### 5. 예외가 `CompletionException` 으로 감싸인다

```
Request processing failed: java.util.concurrent.CompletionException:
    com.example.demo.adapter.OpenBankingException: 오픈뱅킹 응답 실패
```

`@ExceptionHandler` 를 쓸 때 `OpenBankingException` 으로 잡히지 않는다.
`cause` 를 벗겨야 한다. 6단계에서는 원래 예외가 그대로 올라온다.

## 시연 팁

- `[v4] 진입` / `[v4] 반환` 두 줄만 먼저 확대해서 보여준다. 이게 이 단계의 성과 전부다.
- 그 다음 코드를 띄우고 **1단계 코드와 나란히 놓는다.** 성과의 대가가 한눈에 보인다.
- `?failFast=true` 로 형제가 안 죽는 걸 보여주고, 6단계에서 같은 명령을 다시 친다.

## 다음 단계로

> 톰캣 스레드도 반납하고, 코드도 1단계처럼 읽히게 할 수는 없나?

5단계에서 코루틴이 그 둘을 동시에 가져간다. → [05](05-coroutine-structured-concurrency.md)
