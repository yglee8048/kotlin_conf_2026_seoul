# 실전 팁. 비동기 호출 + @Transactional 의 위험성

> 3단계에서 다룬 ThreadLocal 문제와 뿌리가 같다.
> 다만 결과가 로그 한 줄이 비는 정도가 아니라 **데이터 정합성이 깨지는 것**이다.

## 코드

- `tip/PointService.kt`
- `tip/PointRepository.kt`
- `tip/PointController.kt`
- `src/main/resources/schema.sql` (임베디드 H2)

"포인트 적립" 은 DB 쓰기가 두 개다. 둘은 반드시 함께 성공하거나 함께 실패해야 한다.

1. `point_account.balance` 증가
2. `point_history` 이력 적재

## 실행

```bash
# 1) 동기 + 실패
curl -X POST "localhost:8080/api/tips/point/reset?userId=user-1"
curl -X POST "localhost:8080/api/tips/point/charge?userId=user-1&amount=1000&mode=sync&fail=true"
sleep 1 && curl "localhost:8080/api/tips/point?userId=user-1"

# 2) 비동기 + 실패
curl -X POST "localhost:8080/api/tips/point/reset?userId=user-1"
curl -X POST "localhost:8080/api/tips/point/charge?userId=user-1&amount=1000&mode=async&fail=true"
sleep 1 && curl "localhost:8080/api/tips/point?userId=user-1"
```

## 측정 결과

```
### mode=sync fail=true  — 정상
{"result":"rolled-back","mode":"sync"}
최종 상태: {"balance":0,"historyCount":0}

  [chargeSync] 시작
  [addBalance]     트랜잭션 활성=true  이름=...PointService.chargeSync
  [insertHistory]  트랜잭션 활성=true  이름=...PointService.chargeSync
  적립 실패로 롤백됨
```

```
### mode=async fail=true  — 깨진다
{"result":"rolled-back","mode":"async"}
최종 상태: {"balance":0,"historyCount":1}     ← ★

  [chargeAsync] 시작
  [addBalance]     트랜잭션 활성=true  이름=...PointService.chargeAsync
  적립 실패로 롤백됨
  [insertHistory]  트랜잭션 활성=false 이름=null      ← ★
```

**`historyCount=1` 이 이 팁의 전부다.**
잔액은 롤백됐는데 이력만 남았다. "적립되지 않았는데 적립 이력이 있는" 상태다.

## 말할 내용

### 1. 왜 이렇게 되나

트랜잭션은 `TransactionSynchronizationManager` 라는 **ThreadLocal** 에 묶여 있다.

3단계에서 MDC 와 `CallContext` 가 executor 스레드로 안 넘어가던 것과 **완전히 같은 구조**다.
다른 점은 결과의 무게뿐이다.

```
3단계:  로그에 traceId 가 안 찍힘        → 디버깅이 불편
여기:   트랜잭션이 안 따라감              → 데이터가 깨짐
```

로그에 `트랜잭션 활성=false` 를 찍어두면 이게 즉시 보인다.
발표에서는 이 한 줄만 형광펜으로 짚으면 된다.

### 2. 흔한 동기가 함정을 부른다

> "이력 적재는 느리니까 비동기로 빼자."

2단계에서 접속 기록 적재를 `@Async` 로 뺀 것과 **똑같은 판단**이다.
그때는 맞았다. 접속 기록은 트랜잭션 밖의 작업이었기 때문이다.

**차이는 '같은 원자성 단위 안에 있는가' 다.**
같은 트랜잭션에 속해야 하는 작업을 비동기로 빼면 그 순간 원자성이 깨진다.

### 3. 더 고약한 변종

**(a) 읽기 경합**
비동기 스레드가 아직 커밋되지 않은 데이터를 읽으려 하면 못 본다.
타이밍에 따라 결과가 달라지므로 **테스트로 잡기 어렵다.**
운영에서 부하가 올라갔을 때만 재현되는 종류의 버그다.

**(b) 락 대기 / 데드락**
비동기 쪽이 바깥 트랜잭션이 잡고 있는 행을 건드리면 락을 기다린다.
바깥은 비동기를 기다리지 않으므로 보통은 그냥 늦어지지만,
`Future.get()` 으로 기다리는 코드가 있으면 **데드락**이 된다.

**(c) `@Transactional` + `@Async` 를 같은 메서드에**
트랜잭션이 비동기 스레드에서 시작된다. 호출자는 커밋 여부를 알 수 없고
롤백을 유도할 수도 없다. 조용히 동작하지만 제어권이 사라진다.

### 4. 그럼 어떻게 하나

- 같은 원자성이 필요하면 **비동기로 빼지 않는다.** 가장 확실하다.
- 응답만 빠르게 하고 싶으면 **커밋 이후로 미룬다.**
  ```kotlin
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  fun onCharged(event: PointCharged) { ... }
  ```
  이러면 "커밋된 사실" 에만 반응하므로 정합성이 깨지지 않는다.
- 이력이 유실되면 안 된다면 **outbox 패턴**을 쓴다.
  같은 트랜잭션에 outbox 행을 넣고, 별도 워커가 읽어서 처리한다.

### 5. 코루틴에서는 더 위험하다

```kotlin
@Transactional
suspend fun charge(...) {
    repository.addBalance(...)
    withContext(Dispatchers.IO) { repository.insertHistory(...) }  // 스레드가 바뀐다
}
```

**컴파일 에러가 나지 않는다.** `@Async` 는 최소한 "다른 메서드를 호출한다" 는
시각적 신호라도 있지만, `withContext` 는 그냥 블록이다.

게다가 suspend 함수는 **중간에 스레드가 바뀔 수 있다.**
`Dispatchers.Unconfined` 로 시작해도 재개 시점에 다른 스레드일 수 있다.

> 규칙: **트랜잭션 경계 안에서 스레드를 넘기지 않는다.**

코루틴에서 트랜잭션을 다뤄야 한다면
`TransactionTemplate` 을 `runInterruptible`/`withContext` **바깥**에 두거나,
트랜잭션 블록 전체를 하나의 blocking 호출로 묶어라.

### 6. 이 팁을 발표 어디에 넣을까

2단계(`@Async` 도입) 직후나, 3단계(ThreadLocal 전파) 직후가 자연스럽다.
"ThreadLocal 이 안 따라간다" 는 이야기의 가장 비싼 사례이기 때문이다.

시간이 없으면 3단계 안에서 한 문장으로 처리해도 된다.

> "참고로 트랜잭션도 ThreadLocal 입니다. 그래서 비동기로 빼면 안 따라갑니다."

## 시연 팁

- `historyCount` 숫자 하나만 보여주면 끝난다. 로그는 보조다.
- 두 명령을 미리 스크립트로 만들어두고 연속 실행한다. `sync` → `async` 순서.
- `트랜잭션 활성=false` 줄을 확대해서 보여준다.

## 관련

- [03](03-context-propagation.md) — ThreadLocal 전파 일반론
- [05](05-coroutine-structured-concurrency.md) — CoroutineScope 를 Bean 으로 (다른 실전 팁)
