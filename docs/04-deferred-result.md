# 4단계. DeferredResult 로 톰캣 스레드 반납

> 응답 시간은 그대로다. 대신 톰캣 스레드가 자유로워지고, 코드는 읽기 어려워진다.

## 코드

- `controller/HomeItemV4Controller.kt` → `GET /api/v4/home/items`
- `service/HomeItemServiceV4.kt`
- `config/ContextAwareAsyncConfig.coreBankTaskExecutorV3` (이번에 추가)

3단계까지 남아 있던 마지막 낭비를 없앤다.

> 병렬로 만들어도 톰캣 스레드는 응답이 완성될 때까지 계속 붙잡혀 있었다.

```kotlin
// 서비스가 값이 아니라 CompletableFuture 를 반환한다
fun getHomeItemsV4(userId: UserId, failFast: Boolean = false): CompletableFuture<List<HomeItem>> =
    CompletableFuture
        .supplyAsync({ coreBankAdapter.getAccounts(userId) }, coreBankTaskExecutor)
        .thenCompose { accounts -> composeRest(userId, accounts, failFast) }

// 컨트롤러는 DeferredResult 를 반환하고 즉시 끝난다
val deferredResult = DeferredResult<List<HomeItem>>(3_000)
service.getHomeItemsV4(userId).whenComplete { result, error -> ... }
return deferredResult
```

## 측정 결과

```
V3  0.83s   톰캣 스레드 점유 800ms
V4  0.97s   톰캣 스레드 점유 2ms
```

```
54.964 V[http-nio-8080-exec-1] [trace=TR-V4] HomeItemV4Controller   : [v4] 진입
54.966 P[core-bank-v3-1      ] [trace=TR-V4] CoreBankAdapter        : [getAccounts] start
54.966 P[http-nio-8080-exec-1] [trace=TR-V4] HomeItemV4Controller   : [v4] 반환 (톰캣 스레드 반납)   ← 2ms
55.270 P[core-bank-v3-1      ] [trace=TR-V4] CoreBankAdapter        : [getAccounts] end
55.272 P[home-info-v3-1      ] [trace=TR-V4] HomeItemInfoRepository : [getHomeItemInfos] start   ┐ 병렬
55.273 P[open-banking-v3-1   ] [trace=TR-V4] OpenBankingAdapter     : [getBalances] start        ┘
55.275 P[user-log-v3-1       ] [trace=TR-V4] UserLogRepository      : [saveEvent] start
55.476 P[home-info-v3-1      ] [trace=TR-V4] HomeItemInfoRepository : [getHomeItemInfos] end
55.773 P[open-banking-v3-1   ] [trace=TR-V4] OpenBankingAdapter     : [getBalances] end
                                            ★ 여기서 응답 반환 (톰캣 스레드는 이미 딴 일 하는 중)
55.981 P[user-log-v3-1       ] [trace=TR-V4] UserLogRepository      : [saveEvent] end
```

**시연 포인트**: `[v4] 진입` 과 `[v4] 반환` 이 **2ms 차이**로 붙어 있는 것.
1단계는 이 자리에서 1.8초, 2·3단계는 0.8초를 붙잡고 있었다.

## 말할 내용

### 1. 톰캣 스레드를 반납하려면 체인 전체가 비동기여야 한다

3단계까지는 코어뱅킹 조회(300ms)를 톰캣 스레드에서 그냥 했다.
그 상태로 `DeferredResult` 만 반환하면 톰캣 스레드는 여전히 300ms 붙잡힌다.

그래서 **executor 를 하나 더 만들었다.** (`core-bank-v3-`)

> 비동기로 만들려면 blocking 이 하나라도 남으면 안 되고,
> 그 말은 호출하는 하위 시스템마다 executor 를 준비해야 한다는 뜻이다.

2단계에서 "하위 시스템이 늘 때마다 Executor 를 다시 고민해야 한다" 고 적어둔 항목이
여기서 실제로 청구서로 돌아온다.

### 2. 얻은 것과 잃은 것

| | 3단계 | 4단계 |
|---|---|---|
| 응답 시간 | 0.83s | 0.97s |
| 톰캣 스레드 점유 | 800ms | **2ms** |
| 코드가 위에서 아래로 읽힘 | O | **X** |
| 조립 로직 위치 | 마지막 return 문 | 콜백 안 |
| executor 개수 | 3 | **4** |

응답 시간이 오히려 조금 늘었다. 스레드 hop 이 하나 늘었기 때문이다.
**4단계는 응답 시간을 줄이는 게 아니라 처리량을 늘리는 최적화다.**
이 구분을 명확히 해야 한다. Little's Law 로 돌아가서,
톰캣 스레드 200개로 처리할 수 있는 초당 요청 수가 250 → 100000 수준으로 바뀐다.

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
