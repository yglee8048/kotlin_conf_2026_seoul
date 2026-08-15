# 7단계. 코루틴에서의 컨텍스트 전파 — 명시적으로, 그리고 자동으로

> 먼저 손으로 하는 방법(`ThreadContextElement`)을 보여준 뒤,
> 3단계의 decorator 와 함께 그것까지 걷어내는 accessor 를 도입한다.

## 코드

- `context/CallContextElement.kt` (도입부 — 명시적 전파)
- `context/CallContextThreadLocalAccessor.kt`
- `context/MdcThreadLocalAccessor.kt`
- `config/ContextPropagationConfig.kt`
- `service/HomeItemServiceV7.kt` → `GET /api/v7/home/items`
- `controller/DemoToggleController.kt` (시연용 on/off)

## 0. 도입부 — 일단 손으로 전파해보자

5·6단계 로그에서 코어뱅킹 이후 worker 스레드는 전부 `[trace=none] ctx=없음` 이었다.
코루틴에서 이걸 해결하는 **기본기**부터 보여준다. 전파할 타입마다 element 를 만들고,
코루틴 시작점마다 `+` 로 얹으면 된다.

```kotlin
runBlocking(MDCContext() + CallContextElement()) { ... }   // MDC 용은 kotlinx 가 제공
applicationCoroutineScope.launch(MDCContext() + CallContextElement()) { ... }
```

```kotlin
class CallContextElement(
    private val callContext: CallContext? = CallContextHolder.get(),        // 캡처
) : ThreadContextElement<CallContext?> {
    override fun updateThreadContext(ctx: CoroutineContext): CallContext? =
        CallContextHolder.get().also { CallContextHolder.set(callContext) }  // 주입 — 재개될 때마다
    override fun restoreThreadContext(ctx: CoroutineContext, old: CallContext?) =
        CallContextHolder.set(old)                                           // 원복 — 중단될 때마다
}
```

3단계 `CallContextTaskDecorator` 와 **같은 캡처 → 주입 → 원복 모델**이다. 차이는 호출 시점이다.
decorator 는 작업 제출/실행 1회지만, 코루틴은 suspension 마다 스레드를 갈아탈 수 있어
element 가 **재개·중단마다** 넣었다 뺐다 해준다.

실측(스크래치 테스트):

```
--- element 없이 ---
[DefaultDispatcher-worker-1] ctx=null           mdc=null
--- element 있이 ---
[DefaultDispatcher-worker-1] ctx=MOBILE/TR-1    mdc=TR-1
--- 블록 뒤: 원래 컨텍스트로 원복됨
```

**그런데 이건 3단계의 부채가 모양만 바꾼 것이다.**
전파할 타입마다 element 클래스, 코루틴 시작점마다 `+` — 하나라도 빠뜨리면 조용히 안 된다.
서비스 코드에 전파 코드가 섞이는 것도 그대로다. 그래서 아래로 넘어간다.

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
50.128 P[http-nio-8080-exec-2] [trace=TR-OFF] CoreBankAdapter        : [getAccounts] start   ctx=MOBILE/TR-OFF
50.434 P[user-log-v7-2       ] [trace=none]   UserLogRepository      : [saveEvent] start     ctx=없음
50.434 P[tDispatcher-worker-1] [trace=none]   OpenBankingAdapter     : [getBalances] start   ctx=없음
50.435 P[tDispatcher-worker-3] [trace=none]   HomeItemInfoRepository : [getHomeItemInfos] start ctx=없음

### accessor ON
47.939 P[http-nio-8080-exec-4] [trace=TR-V7] CoreBankAdapter        : [getAccounts] start   ctx=MOBILE/TR-V7
48.256 P[user-log-v7-1       ] [trace=TR-V7] UserLogRepository      : [saveEvent] start     ctx=MOBILE/TR-V7
48.259 P[tDispatcher-worker-3] [trace=TR-V7] HomeItemInfoRepository : [getHomeItemInfos] start ctx=MOBILE/TR-V7
48.259 P[tDispatcher-worker-1] [trace=TR-V7] OpenBankingAdapter     : [getBalances] start   ctx=MOBILE/TR-V7
```

**코루틴 dispatcher(`DefaultDispatcher-worker-N`)와 `@Async` executor(`user-log-v7-N`)가
같은 등록 하나로 동시에 해결된다.** 이게 이 단계의 핵심 장면이다.

## 말할 내용

### 1. 앞의 두 방식과 무엇이 다른가

| | 3단계 decorator | 도입부 element | 7단계 accessor |
|---|---|---|---|
| 전파 로직 | 직접 구현 | 직접 구현 | Spring + micrometer 제공 |
| 적용 범위 | decorator 를 건 executor 만 | element 를 얹은 코루틴만 | 등록된 accessor 를 아는 **모든 경계** |
| 대상 추가 비용 | decorator 수정 | element 클래스 추가 | accessor 클래스 추가 |
| 호출부 수정 | 불필요 | **시작점마다 `+`** | 불필요 |
| 코루틴 dispatcher | ✗ | O | **O** |
| executor | O | ✗ | **O** |

앞의 둘은 각자 한쪽만 커버했고, 둘 다 **빠뜨리면 조용히 안 되는** 방식이었다.
accessor 는 등록 한 번으로 양쪽을 덮는다.

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

> **(a)·(b) 에서는 도입부의 element 가 그대로 답이 된다.**
> `applicationScope.launch(MDCContext() + CallContextElement()) { ... }`
> 자동 전파는 기본을 대체하는 게 아니라 **대부분의 경우를 덮어주는 것**이고,
> 덮이지 않는 자리에서는 손으로 얹는다. 그래서 도입부를 먼저 보여준다.

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
