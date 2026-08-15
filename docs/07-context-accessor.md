# 7단계. Spring 7 accessor 로 컨텍스트 자동 전파

> 3단계에서 손으로 짠 decorator 를 걷어낸다. 코루틴 dispatcher 까지 한 번에 해결된다.

## 코드

- `context/CallContextThreadLocalAccessor.kt`
- `context/MdcThreadLocalAccessor.kt`
- `config/ContextPropagationConfig.kt`
- `service/HomeItemServiceV7.kt` → `GET /api/v7/home/items`
- `controller/DemoToggleController.kt` (시연용 on/off)

```kotlin
class CallContextThreadLocalAccessor : ThreadLocalAccessor<CallContext> {
    override fun key(): Any = "callContext"
    override fun getValue(): CallContext? = CallContextHolder.get()
    override fun setValue(value: CallContext) = CallContextHolder.set(value)
    override fun setValue() = CallContextHolder.clear()
}
```

등록은 두 줄이다.

```kotlin
Hooks.enableAutomaticContextPropagation()
ContextRegistry.getInstance()
    .registerThreadLocalAccessor(CallContextThreadLocalAccessor())
    .registerThreadLocalAccessor(MdcThreadLocalAccessor())
```

executor 쪽은 decorator 를 갈아끼우기만 한다.

```kotlin
setTaskDecorator(CallContextTaskDecorator())        // 3단계: 직접 구현
setTaskDecorator(ContextPropagatingTaskDecorator()) // 7단계: Spring 이 제공
```

**서비스 코드(`HomeItemServiceV7`)는 5단계와 로직이 같다. 추가한 코드가 없다.**

## 측정 결과

같은 엔드포인트를 accessor 등록만 껐다 켜서 두 번 호출한다.

```bash
curl -X POST "localhost:8080/api/demo/context-accessors?enabled=false"
curl "localhost:8080/api/v7/home/items?value=user-1" -H "X-Trace-Id: TR-OFF"

curl -X POST "localhost:8080/api/demo/context-accessors?enabled=true"
curl "localhost:8080/api/v7/home/items?value=user-1" -H "X-Trace-Id: TR-V7"
```

```
### accessor OFF  (= 6단계까지의 상태)
50.128 P[tDispatcher-worker-1] [trace=none] CoreBankAdapter        : [getAccounts] start   ctx=없음
50.434 P[user-log-v7-2       ] [trace=none] UserLogRepository      : [saveEvent] start     ctx=없음
50.434 P[tDispatcher-worker-1] [trace=none] OpenBankingAdapter     : [getBalances] start   ctx=없음
50.435 P[tDispatcher-worker-3] [trace=none] HomeItemInfoRepository : [getHomeItemInfos] start ctx=없음

### accessor ON
47.939 P[tDispatcher-worker-1] [trace=TR-V7] CoreBankAdapter        : [getAccounts] start   ctx=MOBILE/TR-V7
48.256 P[user-log-v7-1       ] [trace=TR-V7] UserLogRepository      : [saveEvent] start     ctx=MOBILE/TR-V7
48.259 P[tDispatcher-worker-3] [trace=TR-V7] HomeItemInfoRepository : [getHomeItemInfos] start ctx=MOBILE/TR-V7
48.259 P[tDispatcher-worker-1] [trace=TR-V7] OpenBankingAdapter     : [getBalances] start   ctx=MOBILE/TR-V7
```

**코루틴 dispatcher(`DefaultDispatcher-worker-N`)와 `@Async` executor(`user-log-v7-N`)가
같은 등록 하나로 동시에 해결된다.** 이게 이 단계의 핵심 장면이다.

## 말할 내용

### 1. 3단계와 무엇이 다른가

| | 3단계 | 7단계 |
|---|---|---|
| 전파 로직 | 직접 구현 (캡처/주입/원복) | Spring + micrometer 가 제공 |
| 적용 범위 | 내가 decorator 를 건 executor 만 | 등록된 accessor 를 아는 모든 경계 |
| 대상 추가 비용 | decorator 클래스 수정 | accessor 클래스 하나 추가 |
| 코루틴 dispatcher | ✗ | **O** |

3단계 노트 마지막에 "새로운 부채가 생겼다 — 전파할 항목이 늘 때마다 decorator 를
고쳐야 한다" 고 적어뒀다. 그 부채가 여기서 정리된다.

정확히는 **없어진 게 아니라 옮겨갔다.** 목록을 관리하는 주체가
"호출부/실행 경계" 에서 "타입" 으로 이동했다.
SecurityContext 를 추가하려면 accessor 클래스를 하나 더 만들면 되고,
호출부와 executor 설정은 손대지 않는다.

### 2. 어떻게 코루틴까지 되는가

```kotlin
// CoroutinesUtils (spring-core 7.0.x)
CoroutineContext context = Hooks.isAutomaticContextPropagationEnabled()
        ? Dispatchers.getUnconfined().plus(new PropagationContextElement())
        : Dispatchers.getUnconfined();
```

Spring MVC 가 suspend 컨트롤러를 호출할 때 CoroutineContext 에
`PropagationContextElement` 를 얹는다. 이 element 는
**코루틴이 재개될 때마다** 등록된 accessor 로 ThreadLocal 을 복원한다.

핵심은 **dispatcher 가 아니라 재개 시점에 걸린다**는 것이다.
그래서 10단계에서 dispatcher 를 가상 스레드로 갈아치워도 그대로 동작한다.
3단계 방식이었다면 새 executor 에 decorator 를 또 달아야 했다.

### 3. 함정 — Boot 4 에는 `spring.reactor.context-propagation` 이 없다

Spring Boot 3.x 에는 이 프로퍼티가 있었다. **Boot 4 에는 Reactor 오토컨피그가 없다.**
그래서 직접 켜야 한다.

```kotlin
Hooks.enableAutomaticContextPropagation()
```

이걸 빼먹으면 **accessor 를 아무리 등록해도 코루틴 쪽 전파가 조용히 안 된다.**
에러도 경고도 없다. 실제로 이 데모를 만들면서 처음에 여기서 막혔다.
기동 로그에 상태를 찍어두는 이유다.

```
Reactor 자동 컨텍스트 전파 활성=true
ThreadLocalAccessor 등록됨: [micrometer.observation, callContext, mdc]
```

`micrometer.observation` 은 actuator 가 ServiceLoader 로 자동 등록한 것이다.
**이미 이 메커니즘 위에서 돌고 있는 것이 있었다**는 걸 보여주기 좋다.

### 4. 자동 전파가 안 걸리는 경우

**(a) `runBlocking` (5단계)**
`CoroutinesUtils` 를 거치지 않으므로 `PropagationContextElement` 가 안 붙는다.
"코루틴을 쓰면 된다" 가 아니라 **"프레임워크가 코루틴 진입점을 알아야 한다"** 는 뜻이다.

**(b) 다른 스코프로 내보낸 작업 (5단계의 `applicationCoroutineScope.launch`)**
부모가 다르므로 element 를 물려받지 않는다. v5 로그에서 `saveEvent` 만
`ctx=없음` 인 이유다. **의도적으로 스코프를 벗어난 작업은 컨텍스트도 함께 벗어난다.**
이건 버그가 아니라 일관성이다.

그래서 v7 은 접속 기록 적재를 `ContextPropagatingTaskDecorator` 를 건 executor
(`user-log-v7-`)로 보낸다. 제출 시점 스냅샷에 담기므로 전파된다.

**(c) `StructuredTaskScope` (12단계)**
fork 한 가상 스레드에는 ThreadLocal 이 안 따라간다. `ScopedValue` 를 별도로 써야 한다.

### 5. 시연용 토글에 대해

`POST /api/demo/context-accessors?enabled=false|true` 는 데모 전용이다.
`ContextRegistry` 는 `removeThreadLocalAccessor(key)` 를 지원하므로
런타임에 등록을 뺐다 넣을 수 있다.

실무에서 이럴 일은 없지만, **재시작 없이 같은 엔드포인트로 before/after 를
보여줄 수 있다**는 게 발표에서는 크다.

## 시연 팁

- 토글 → curl → 토글 → curl 을 한 화면에서 연속으로 친다. 슬라이드보다 빠르다.
- `GET /api/demo/context-accessors` 로 등록 목록을 보여주면 상태가 명확해진다.
- 3단계 `CallContextTaskDecorator.kt` 를 띄워놓고 "이 파일이 통째로 없어진다" 고 말한다.

## 다음 단계로

여기까지가 "Virtual Thread 이전" 이다. 이제 프로파일을 바꿔 재시작한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=vt'
```

→ [08](08-virtual-thread.md)
