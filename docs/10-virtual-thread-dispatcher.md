# 10단계. Virtual Thread Dispatcher

> 9단계에서 발견한 마지막 천장(`Dispatchers.IO` 의 64개)을 없앤다.

## 코드

- `config/VirtualThreadConfig.kt`
- `service/HomeItemServiceV10.kt` → `GET /api/v10/home/items`

```kotlin
@Bean(name = [VIRTUAL_THREAD_DISPATCHER], destroyMethod = "close")
fun virtualThreadDispatcher(): ExecutorCoroutineDispatcher {
    val threadFactory = Thread.ofVirtual().name("vt-dispatch-", 0).factory()
    return Executors.newThreadPerTaskExecutor(threadFactory).asCoroutineDispatcher()
}
```

서비스의 병렬 조회는 7단계에서 **dispatcher 인자만 바뀐다.**
코어뱅킹 호출은 계속 톰캣 스레드에서 blocking 으로 실행한다.

```kotlin
blockingIo { ... }                          // 7단계: Dispatchers.IO
blockingIo(virtualThreadDispatcher) { ... } // 10단계
```

## 측정 결과

```
19.932 V[tomcat-handler-4] [trace=TR-11] CoreBankAdapter        : [getAccounts] start   ctx=UNKNOWN/TR-11
20.237 V[vt-dispatch-1 ] [trace=TR-11] HomeItemInfoRepository : [getHomeItemInfos] start
20.239 V[vt-dispatch-2 ] [trace=TR-11] OpenBankingAdapter     : [getBalances] start
total = 0.83s
```

코어뱅킹 이후 병렬 조회의 `P[DefaultDispatcher-worker-N]` 이 `V[vt-dispatch-N]` 이 됐다.
**응답 시간은 그대로다.** 동시성이 낮을 때는 아무 이득이 없다는 걸 먼저 인정한다.

## 말할 내용

### 1. `Dispatchers.IO` 에 왜 64라는 숫자가 있었나

2단계에서 executor 크기를 고민했던 이유와 **정확히 같다.**
플랫폼 스레드가 비싸기 때문이다.

즉 코루틴을 써도 **blocking 호출을 하는 한** 플랫폼 스레드 개수라는 천장은
그대로 남아 있었다. 코루틴은 그 위에서 스케줄링을 잘할 뿐이다.

> `Dispatchers.IO` 는 코루틴이 blocking 세계와 타협한 흔적이다.

가상 스레드 dispatcher 에서는 이 타협이 필요 없다. 작업마다 스레드를 만들면 된다.

### 2. 2단계의 executor 표를 다시 꺼낸다

| Bean | core/max | queue | 거부 정책 | 크기 근거 |
|---|---:|---:|---|---|
| `homeInfoTaskExecutor` | 10/10 | 50 | CallerRuns | DB connection pool |
| `openBankingTaskExecutor` | 30/30 | 100 | Abort | 상대 시스템 처리량 |
| `logTaskExecutor` | 5/10 | 500 | Discard | 응답 경로 밖 |

이 표에서 **'스레드가 비싸서' 였던 부분이 전부 사라진다.**
core/max/queue 를 정할 필요가 없다.

**그런데 사라지지 않는 것이 있다.**

- DB connection pool 은 여전히 10개다
- 오픈뱅킹 상대 시스템은 여전히 30 동시 호출이 한계다

> 스레드 풀은 **'실행 자원 재사용'** 과 **'동시성 제한'** 이라는
> 서로 다른 두 역할을 겸하고 있었다.
> 가상 스레드는 앞의 역할만 없앤다. 뒤의 역할은 **따로 챙겨야 한다.**

오히려 지금은 상한이 아예 없어서 오픈뱅킹에 동시 호출 3000개를 날릴 수 있다.
**8단계보다 위험해졌다.** → 11단계

이 "겸업 분리" 가 8~11단계를 관통하는 이야기다.

### 3. 컨텍스트 전파는 따라온다 (확인)

dispatcher 를 통째로 갈았는데 병렬 조회의 `ctx=UNKNOWN/TR-11` 이 그대로다.

7단계에서 `PropagationContextElement` 가 **dispatcher 가 아니라 코루틴 재개 시점**에
걸린다고 했던 이유다. 3단계 방식이었다면 이 새 executor 에 decorator 를 또 달아야 했다.

**추상화가 제대로 된 지점을 잡으면 아래를 갈아도 위가 안 바뀐다.**

### 4. 여전히 `runInterruptible` 이 필요하다

가상 스레드라고 취소가 저절로 되지 않는다.

```
취소 → 인터럽트 → blocking 코드 중단
```

이 사슬은 똑같이 필요하다.

덤이 하나 있다. 5단계 실측에서 플랫폼 스레드의 소켓 read 는 인터럽트를 무시했지만,
**가상 스레드 위의 소켓 read 는 인터럽트에 반응한다** (JEP 444, 실측 5ms).
플랫폼 스레드에서 "끝까지 돌던" blocking I/O 취소가 VT dispatcher 에서는 실제로 끊긴다.

> **가상 스레드는 스레드를 싸게 만들 뿐, 수명 관리를 대신해주지 않는다.**

이 발표의 결론이 여기서 한 번 더 나온다.

### 5. 실전 주의

**(a) 무제한이 항상 좋은 건 아니다**
`newVirtualThreadPerTaskExecutor` 는 큐가 없다. 백프레셔가 사라진다.
큐 대기라는 신호가 없어지면 과부하를 늦게 알아챈다.

**(b) `close()` 를 잊지 말 것**
`asCoroutineDispatcher()` 가 만든 dispatcher 는 `Closeable` 이다.
`destroyMethod = "close"` 로 컨텍스트 종료에 맡긴다.

**(c) CPU 바운드 작업에는 쓰지 말 것**
가상 스레드는 I/O 대기에서 캐리어를 반납하는 게 이득이다.
CPU 를 계속 쓰는 작업은 `Dispatchers.Default` 가 맞다.

**(d) `Dispatchers.LOOM` 은 아직 없다**
kotlinx.coroutines 에 정식 가상 스레드 dispatcher 는 없다.
직접 만들어야 한다. (이 파일이 그 예다)

## 시연 팁

- v7 과 v10 로그를 나란히 놓고 `P[DefaultDispatcher-worker-1]` vs `V[vt-dispatch-1]` 만 짚는다.
- **응답 시간이 같다는 걸 반드시 말한다.** 여기서 과장하면 신뢰를 잃는다.
- 2단계 executor 표를 다시 띄우고 항목을 하나씩 지워가며 "이건 남는다" 를 표시한다.

## 다음 단계로

없어진 상한을 되찾는다. → [11](11-resilience.md)
