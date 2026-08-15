# 1단계. blocking MVC 에서의 일반적인 홈 화면 조회

> 출발점. 이후 모든 단계가 이 코드와 비교된다.

## 코드

- `controller/HomeItemController.kt` → `GET /api/v1/home/items`
- `service/HomeItemService.kt`

특별한 것이 없다는 게 요점이다. 위에서 아래로 읽히는 평범한 blocking 코드다.

```kotlin
fun getHomeItems(userId: UserId): List<HomeItem> {
    val accounts = coreBankAdapter.getAccounts(userId)           // 300ms
    if (accounts.isEmpty()) return emptyList()

    val homeItemInfosByAccountId = homeItemInfoRepository        // 200ms
        .getHomeItemInfos(accountIds).associateBy { it.accountId }

    val openBankBalancesByAccountId = openBankingAdapter         // 500ms
        .getBalances(openBankAccountIds).associateBy { it.accountId }

    userLogRepository.saveEvent(userId, UserEvent.GET_HOME)      // 700ms

    return accounts.map { /* 조립 */ }
}
```

## 측정 결과

```
total = 1.84s
```

로그 (전 구간이 **하나의 톰캣 스레드**):

```
23.690 P[http-nio-8080-exec-6] [trace=TRACE-V1] CoreBankAdapter        : [getAccounts] start
24.000 P[http-nio-8080-exec-6] [trace=TRACE-V1] CoreBankAdapter        : [getAccounts] end
24.000 P[http-nio-8080-exec-6] [trace=TRACE-V1] HomeItemInfoRepository : [getHomeItemInfos] start
24.208 P[http-nio-8080-exec-6] [trace=TRACE-V1] HomeItemInfoRepository : [getHomeItemInfos] end
24.208 P[http-nio-8080-exec-6] [trace=TRACE-V1] OpenBankingAdapter     : [getBalances] start
24.719 P[http-nio-8080-exec-6] [trace=TRACE-V1] OpenBankingAdapter     : [getBalances] end
24.721 P[http-nio-8080-exec-6] [trace=TRACE-V1] UserLogRepository      : [saveEvent] start
25.421 P[http-nio-8080-exec-6] [trace=TRACE-V1] UserLogRepository      : [saveEvent] end
```

300 + 200 + 500 + 700 = 1700ms. 나머지는 프레임워크 오버헤드.

## 말할 내용

### 1. 이 코드의 장점부터 인정하고 시작한다

- 위에서 아래로 읽힌다. 실행 순서 = 코드 순서.
- 디버거로 한 줄씩 따라갈 수 있다. 스택 트레이스가 온전하다.
- `ThreadLocal` 이 그냥 동작한다. MDC, SecurityContext, 트랜잭션 동기화 전부 공짜다. (→ 3단계에서 잃는다)
- 예외 처리가 `try/catch` 하나로 끝난다.

**이 장점들은 앞으로 여러 단계에 걸쳐 하나씩 잃게 된다.** 그걸 되찾는 과정이 이 발표다.

### 2. 문제는 blocking 자체가 아니다

1.7초 동안 이 톰캣 스레드가 실제로 CPU 를 쓴 시간은 거의 없다. 전부 I/O 대기다.
그런데 그 1.7초 내내 **플랫폼 스레드 하나를 점유**하고 있다.

> 문제는 blocking 이 아니라, blocking 중에도 비싼 플랫폼 스레드를 붙잡고 있다는 것이다.

여기서 Little's Law 로 연결한다 (스크립트 슬라이드 4).
초당 1000 요청 × 1.7초 = 시스템 안에 평균 1700개의 요청. 톰캣 스레드가 200개면 나머지는 큐에서 대기한다.

### 3. 두 가지 낭비를 구분한다

이 코드에는 성격이 다른 낭비가 두 개 있다.

| 낭비 | 내용 | 해결 단계 |
|---|---|---|
| **순차 실행** | 개인화 조회(200ms)와 오픈뱅킹(500ms)은 서로 의존이 없는데 순서대로 실행 | 2단계 (병렬) |
| **불필요한 대기** | 접속 기록 적재(700ms)는 응답에 안 쓰이는데 기다림 | 2단계 (비동기) |

그리고 이 둘을 해결해도 남는 세 번째 낭비가 있다.

| **스레드 점유** | 병렬로 만들어도 톰캣 스레드는 응답까지 계속 붙잡혀 있음 | 4·6단계 (Deferred / suspend), 8단계 (VT) |

**이 구분을 1단계에서 미리 심어두는 게 중요하다.** 2단계에서 응답 시간이 절반으로 줄어드는 걸 보고
"해결됐다"고 느끼기 쉬운데, 정작 스레드 점유는 그대로이기 때문이다.

### 4. 여기서 던져둘 질문

2단계로 넘어가기 전에 청중에게 남겨둔다.

> 개인화 조회와 오픈뱅킹 조회를 동시에 하고 싶다. 무엇이 필요한가?

답이 "Executor" 라면, 그 다음 질문들이 줄줄이 따라온다는 걸 2단계에서 보여준다.

## 시연 팁

- 로그 창을 띄워두고 **스레드 이름이 하나뿐**이라는 걸 먼저 보여준다.
  2단계에서 이름이 여러 개로 갈라지는 것과 대비된다.
- `time curl ...` 로 1.8초를 체감시킨다. 슬라이드 숫자보다 실제로 기다리는 게 낫다.

## 주의

`getAccounts` 는 이후 두 조회의 **입력**이다. 병렬화할 수 없다.
"전부 병렬로 돌리면 되지 않나" 라는 반응이 나오면 여기를 짚는다.
동시성 설계의 첫 단계는 스레드가 아니라 **의존 그래프를 그리는 것**이다.
