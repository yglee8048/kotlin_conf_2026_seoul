# 9단계. Virtual Thread + Coroutine

> 8단계 질문에 대한 답. 실행이 값싸져도 코루틴이 남는 이유.

## 코드

새 코드 없음. `vt` 프로파일에서 **5·6단계 엔드포인트를 그대로 호출한다.**

```bash
./gradlew bootRun --args='--spring.profiles.active=vt'
curl "localhost:8080/api/v6/home/items?value=user-1"
```

## 측정 결과

```
02.005 V[tomcat-handler-4    ] [trace=VT-6] HomeItemV6Controller   : [v6] 진입
02.010 V[tomcat-handler-4    ] [trace=VT-6] CoreBankAdapter        : [getAccounts] start
02.319 P[tDispatcher-worker-3] [trace=none] UserLogRepository      : [saveEvent] start
02.320 P[tDispatcher-worker-2] [trace=VT-6] HomeItemInfoRepository : [getHomeItemInfos] start
02.320 P[tDispatcher-worker-5] [trace=VT-6] OpenBankingAdapter     : [getBalances] start
02.826 P[tDispatcher-worker-5] [trace=VT-6] HomeItemV6Controller   : [v6] 반환 (진입과 다른 스레드다)
total = 0.86s
```

두 가지가 눈에 띈다.

1. **톰캣은 `V`, 코루틴 dispatcher 는 `P`.** `Dispatchers.IO` 는 여전히 플랫폼 스레드다.
2. 컨텍스트 전파(7단계)는 프로파일과 무관하게 그대로 동작한다.

## 말할 내용

### 1. 8단계의 질문에 답한다

> 실행이 값싸졌다. 그러면 코루틴은 이제 필요 없나?

8단계에서 v1 코드는 가상 스레드 위에서 잘 돌았다. 하지만 v1 은 **순차 실행**이다.
1.9초 걸린다. 가상 스레드는 그걸 0.8초로 만들어주지 않는다.

동시에 호출하려면 여전히 무언가가 필요하다. 그리고 그 "무언가" 를 선택하는 순간
2단계에서 열거한 질문들이 전부 되살아난다.

| 필요한 것 | 가상 스레드가 주는가 |
|---|---|
| 값싼 실행 | **O** |
| 병렬 실행 | X (직접 짜야 함) |
| 부모-자식 관계 | X |
| 실패 시 형제 취소 | X |
| 묶음 timeout | X |
| 컨텍스트 전파 | X |

> **가상 스레드는 코루틴을 대체한 것이 아니라, 코루틴이 필요했던 이유 중
> '값싼 실행' 하나를 제거했다.** 나머지 이유는 그대로 남는다.

이 문장이 이 발표의 결론이고, 9단계가 그 근거다.

### 2. 두 개를 같이 쓰면 각자 잘하는 걸 한다

```
가상 스레드 : 실행을 값싸게 만든다     (런타임)
코루틴      : 수명·실패·취소를 구조화한다 (언어)
```

층이 다르다. 경쟁 관계가 아니다.

실제로 v6 를 `vt` 프로파일에서 돌리면 **양쪽 이득이 그대로 합쳐진다.**
코어뱅킹은 값싼 톰캣 가상 스레드에서 실행하고,
그 뒤 병렬 조회 구간에서는 톰캣 스레드를 반납하며 구조적 동시성도 유지한다.

### 3. 그런데 `Dispatchers.IO` 가 아직 `P` 다

이게 9단계에서 발견하는 문제다.

`Dispatchers.IO` 는 **기본 64개로 제한된 플랫폼 스레드 풀**이다.
(`kotlinx.coroutines.io.parallelism` 으로 조정)

blocking mock 을 `runInterruptible(Dispatchers.IO)` 로 감싸고 있으므로,
동시 요청 65개째부터는 여기서 대기가 생긴다.

> 톰캣의 스레드 천장은 없앴는데, 그 바로 뒤에 다른 천장이 있었다.

가상 스레드를 도입할 때 흔히 놓치는 지점이다.
**병목은 "가장 낮은 천장" 으로 옮겨갈 뿐 사라지지 않는다.**

→ 10단계에서 이 dispatcher 를 가상 스레드로 갈아치운다.

### 4. 컨텍스트 전파는 프로파일과 무관하다 (확인)

병렬 조회 worker 로그의 `[trace=VT-6] ctx=UNKNOWN/VT-6` 이 그대로 살아있다.
7단계에서 `PropagationContextElement` 가 **dispatcher 가 아니라 재개 시점**에
걸린다고 했던 것이 여기서 확인된다.

3단계 방식(executor 마다 decorator)이었다면 톰캣이 가상 스레드가 되든 말든
새 executor 마다 다시 달아야 했을 것이다.

### 5. `saveEvent` 만 `ctx=없음` 인 이유

v5/v6 는 접속 기록 적재를 `ApplicationCoroutineScope` 로 내보낸다.
부모가 다르므로 `PropagationContextElement` 를 물려받지 않는다.

**의도적으로 스코프를 벗어난 작업은 컨텍스트도 함께 벗어난다.**
버그가 아니라 일관성이고, 7단계 노트에 적어둔 내용이 그대로 관측된다.

## 시연 팁

- 8단계 v1 로그와 9단계 v6 로그를 나란히 띄운다.
  `V[tomcat-handler-0]` 하나 vs `V` + `P` 여러 개.
- "가상 스레드가 코루틴을 대체하나?" 슬라이드를 여기서 띄우고 위의 표로 답한다.
- `P[DefaultDispatcher-worker-N]` 에 형광펜을 치고 10단계로 넘긴다.

## 다음 단계로

`Dispatchers.IO` 를 가상 스레드 dispatcher 로 바꾼다. → [10](10-virtual-thread-dispatcher.md)
