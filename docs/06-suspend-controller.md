# 6단계. suspend 컨트롤러

> `runBlocking` 을 걷어내고 `suspend` 로 넓힌다. 경계를 어디에 두느냐의 문제다.

## 코드

- `controller/HomeItemV6Controller.kt` → `GET /api/v6/home/items`
- `service/HomeItemServiceV6.kt`

5단계와의 핵심 diff 는 `suspend` 로 경계를 넓히고 `runBlocking` 을 제거하는 두 군데다.

```diff
- fun getHomeItemsV5(userId: UserId, failFast: Boolean = false): List<HomeItem> {
+ suspend fun getHomeItemsV6(userId: UserId, failFast: Boolean = false): List<HomeItem> = coroutineScope {

-     val (infos, balances) = runBlocking {
-         val homeCardInfoDeferred = async(...) { ... }
-         val openBankBalanceDeferred = async(...) { ... }
-         homeCardInfoDeferred.await() to openBankBalanceDeferred.await()
-     }
+     val homeCardInfoDeferred = async(...) { ... }     // 블록이 그대로 펼쳐진다
+     val openBankBalanceDeferred = async(...) { ... }
```

컨트롤러는 `suspend` 한 단어만 붙는다.

```kotlin
suspend fun getHomeItems(userId: UserId, failFast: Boolean): List<HomeItem> =
    homeItemServiceV6.getHomeItemsV6(userId, failFast)
```

## 측정 결과

```
V4  0.87s   톰캣 스레드 점유 320ms    코드 X
V5  1.00s   톰캣 스레드 점유 828ms    코드 O
V6  0.85s   톰캣 스레드 점유 320ms    코드 O
```

```
05.887 P[http-nio-8080-exec-2] [trace=T6] HomeItemV6Controller   : [v6] 진입
05.888 P[http-nio-8080-exec-2] [trace=T6] CoreBankAdapter        : [getAccounts] start   ← 톰캣 스레드
06.192 P[http-nio-8080-exec-2] [trace=T6] CoreBankAdapter        : [getAccounts] end
06.194 P[tDispatcher-worker-2] [trace=none] UserLogRepository      : [saveEvent] start
06.199 P[tDispatcher-worker-3] [trace=T6] HomeItemInfoRepository : [getHomeItemInfos] start ┐ 병렬
06.199 P[tDispatcher-worker-1] [trace=T6] OpenBankingAdapter     : [getBalances] start      ┘
06.403 P[tDispatcher-worker-3] [trace=T6] HomeItemInfoRepository : [getHomeItemInfos] end
06.700 P[tDispatcher-worker-1] [trace=T6] OpenBankingAdapter     : [getBalances] end
06.701 P[tDispatcher-worker-1] [trace=T6] HomeItemV6Controller   : [v6] 반환 (진입과 다른 스레드다)
```

**관찰 포인트**: `[v6] 진입`과 `getAccounts` 는 `http-nio-8080-exec-2`,
`[v6] 반환` 은 `DefaultDispatcher-worker-1`이다.
코어뱅킹 조회까지는 톰캣 스레드가 담당하고, 그 뒤 첫 suspension 에서 반납된다.

5단계에서는 두 줄이 같은 스레드였고, `getAccounts` 도 톰캣 스레드였다.
그 차이를 로그 두 줄로 보여주면 된다.

## 말할 내용

### 1. 핵심은 "경계를 어디에 두는가"

5단계에서 `runBlocking` 은 **코루틴 세계와 blocking 세계의 경계**였다.
그 경계가 서비스 안쪽에 있는 한, 호출 스레드(톰캣)는 계속 막혀 있다.

경계를 위로 밀어 올리면 — 컨트롤러까지 `suspend` 로 만들면 — 경계 자체가 사라진다.
코어뱅킹은 blocking 으로 실행하고, 그 뒤 첫 suspension 에서 톰캣 스레드가 반납된다.

> **코루틴 도입은 "전부 바꾸기" 가 아니라 "경계를 어디에 둘 것인가" 의 문제다.**
> 5단계는 경계가 서비스 안, 6단계는 컨트롤러 밖. 코드는 거의 같다.

실무에서는 5단계 모양으로 먼저 들여놓고, 준비되는 곳부터 경계를 위로 올리면 된다.
**중간에 멈춰 있어도 동작한다**는 게 중요하다. WebFlux 전환에는 이런 중간 상태가 없다.

### 2. `getAccounts` 는 4단계와 같이 blocking 으로 남겨둔다

비교 조건을 맞추기 위해 6단계에서도 코어뱅킹 호출을 `blockingIo` 로 감싸지 않는다.
4단계와 똑같이 톰캣 스레드에서 약 300ms 동안 실행한다.
이후의 개인화 정보와 오픈뱅킹 잔액 조회는 `blockingIo` 로 넘기고,
그 두 결과를 기다리는 동안에는 톰캣 스레드를 반납한다.

### 3. 어떻게 동작하나

Spring MVC 는 컨트롤러 메서드가 suspend 면 `CoroutinesUtils.invokeSuspendingFunction`
으로 호출해 `Mono` 로 감싸고, 그 결과를 서블릿 async 로 처리한다.

즉 **내부적으로는 4단계와 같은 메커니즘**이다. `DeferredResult` 가 하던 일을
프레임워크가 대신 해준다. 다만 그게 코드에 드러나지 않는다.

> 이걸 위해 `kotlinx-coroutines-reactor` 가 런타임에 필요하다.
> Spring MVC 만 쓰더라도 그렇다. 빠뜨리면 `NoClassDefFoundError` 가 난다.

### 4. 4단계와 최종 비교

| | 4단계 | 6단계 |
|---|---|---|
| 톰캣 스레드 점유 | 320ms (코어뱅킹 구간) | **320ms (코어뱅킹 구간)** |
| 코드가 위에서 아래로 | X | O |
| 구조적 동시성 | X | O |
| 컨트롤러 반환 타입 | `DeferredResult<T>` | **`T`** |
| 예외 | `CompletionException` 으로 감싸짐 | 원래 예외 |
| 하위 시스템마다 executor | 필요 (3개) | **불필요 — dispatcher 하나** |

비동기가 되었는데 **시그니처는 1단계와 같다.**

### 5. 컨텍스트 전파 — 여기서 갈린다

7단계의 accessor 가 등록되어 있으면 **6단계부터는 컨텍스트가 자동으로 전파된다.**
`runBlocking`(5단계)은 `CoroutinesUtils` 를 거치지 않아 `PropagationContextElement` 가
안 붙지만, suspend 컨트롤러는 붙기 때문이다.

```
### accessor OFF
36.407 P[tDispatcher-worker-2] [trace=none] HomeItemInfoRepository : [getHomeItemInfos] start   ctx=없음

### accessor ON
06.199 P[tDispatcher-worker-3] [trace=T6  ] HomeItemInfoRepository : [getHomeItemInfos] start   ctx=UNKNOWN/T6
```

**발표에서는 5·6단계를 보여줄 때 accessor 를 꺼두는 것을 권한다.**
그래야 7단계에서 켜는 순간의 대비가 산다.

```bash
curl -X POST "localhost:8080/api/demo/context-accessors?enabled=false"
```

반대로 "suspend 로 바꿨을 뿐인데 전파가 되네?" 를 먼저 보여주고
7단계에서 "그게 왜 되는지" 를 설명하는 순서도 가능하다. 취향의 문제다.

### 6. 아직 남은 것

`Dispatchers.IO` 는 **기본 64개로 제한된 플랫폼 스레드 풀**이다.
코어뱅킹 이후에는 톰캣 스레드를 반납하지만,
`blockingIo` 호출을 하는 한 플랫폼 스레드 천장은 그대로다. → 10단계

### 7. 나올 만한 질문

**Q. suspend 컨트롤러를 쓰면 WebFlux 가 되는 건가?**
아니다. 서블릿 컨테이너와 blocking MVC 스택 그대로다.
서블릿 async 를 쓸 뿐이고, 여전히 `HttpServletRequest` 도 있고 필터도 그대로 동작한다.

**Q. `Dispatchers.IO` 없이 그냥 suspend 만 붙이면?**
blocking 호출이 톰캣 스레드를 그대로 막는다.
`CoroutinesUtils` 가 `Dispatchers.Unconfined` 로 시작하기 때문이다.
**suspend 를 붙이는 것과 blocking 을 걷어내는 것은 별개다.**

**Q. `@Transactional` 을 suspend 함수에 붙여도 되나?**
위험하다. 트랜잭션은 ThreadLocal 인데 suspend 함수는 중간에 스레드가 바뀔 수 있다.
→ [90](90-tip-async-transaction.md)

## 시연 팁

- 5단계와 6단계 서비스 파일을 `diff` 로 띄운다. 코어뱅킹 호출이 그대로인 점을 보여준다.
- 로그에서 `[진입]` / `[반환]` 의 **스레드 이름 컬럼만** 형광펜으로 짚는다.
- 응답 시간이 거의 같다는 것도 같이 말한다. 이 단계는 지연이 아니라 처리량 최적화다.

## 다음 단계로

컨텍스트 전파를 선언으로 해결한다. → [07](07-context-accessor.md)
