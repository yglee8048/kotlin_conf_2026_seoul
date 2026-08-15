# 단계별 발표 노트

`kotlin-conference-script.md` 가 발표 전체 흐름이라면, 이 폴더는 **데모 코드에 붙는 노트**다.
각 파일은 그 단계에서 실제로 돌려본 결과와, 코드를 보여주며 말할 내용을 담는다.

## 진행 상황

| 단계 | 내용 | 상태 | 노트 |
|---:|---|---|---|
| 1 | blocking MVC 일반적인 홈 화면 조회 | 구현됨 | [01](01-blocking-mvc-sequential.md) |
| 2 | 병렬 호출과 비동기 적용 (CompletableFuture, @Async) | 구현됨 | [02](02-completable-future-async.md) |
| 3 | 커스텀 ThreadLocal / MDC 전파 | 예정 | |
| 4 | controller 에서 Deferred 응답 | 예정 | |
| 5 | Coroutine 적용 (구조적 동시성) | 예정 | |
| 6 | suspend controller | 예정 | |
| 7 | Spring 7 accessor 로 context 자동 전파 | 예정 | |
| 8 | Virtual Thread (1단계 코드 재사용) | 예정 | |
| 9 | Virtual Thread + Coroutine (5단계 코드 재사용) | 예정 | |
| 10 | Virtual Thread dispatcher | 예정 | |
| 11 | 안정성 (concurrency limit, timeout) | 예정 | |
| 12 | 번외: Virtual Thread 로 비동기/병렬 직접 구현 | 예정 | |

## 데모 시나리오 공통

### mock 지연

발표 내내 같은 API 하나(`홈 화면 조회`)를 발전시킨다. 각 하위 시스템은 고정 지연을 갖는다.

| 호출 | 지연 | 성격 |
|---|---:|---|
| `CoreBankAdapter.getAccounts` | 300ms | 코어뱅킹. 이후 두 조회의 **입력**이라 병렬화 불가 |
| `HomeItemInfoRepository.getHomeItemInfos` | 200ms | 개인화 DB |
| `OpenBankingAdapter.getBalances` | 500ms | 외부 HTTP. 가장 느림 |
| `UserLogRepository.saveEvent` | 700ms | 접속 기록. **응답에 안 쓰임** |

지연 값은 각 클래스의 `LATENCY_MILLIS` 상수다.

숫자를 이렇게 고른 이유:

- `getAccounts` 가 앞에 오는 의존이 있어서, "전부 병렬"이 아니라 "**의존 관계를 봐야 한다**"는 걸 보여준다.
- `saveEvent(700ms)` 가 가장 느리다. 응답에 필요 없는 작업이 응답 시간을 지배하는 상황을 만든다.
- `saveEvent(700ms) > getBalances(500ms)` 라서, fire-and-forget 하면 **응답이 나간 뒤에도 로그 작업이 살아있는 것**이 로그에 남는다.

### 계좌 더미 데이터

`getAccounts` 는 항상 3건을 반환한다.

| 계좌 | 타입 | 잔액 출처 | 개인화 정보 |
|---|---|---|---|
| 110-1234-5678 | DEPOSIT | 코어뱅킹 응답에 포함 | 있음 (생활비 통장 / mint) |
| 333-9876-5432 | OPEN_BANK | 오픈뱅킹 조회 필요 | 있음 (비상금 통장 / coral) |
| 777-1111-2222 | OPEN_BANK | 오픈뱅킹 조회 필요 | **없음 → 기본값 fallback** |

세 번째 계좌는 일부러 개인화 정보를 비웠다. 응답에서 `alias: "오픈뱅킹"`, `color: default` 로 나오는 걸로
"모든 계좌에 개인화 행이 있는 게 아니다" 를 보여준다.

### 호출 방법

`userId` 는 `UserId` data class 바인딩이라 쿼리 파라미터 이름이 `value` 다.

```bash
curl -s -w "\ntotal=%{time_total}s\n" "http://localhost:8080/api/v1/home/items?value=user-1"
curl -s -w "\ntotal=%{time_total}s\n" "http://localhost:8080/api/v2/home/items?value=user-1"
```

### 관찰 포인트

모든 mock 은 진입/종료 시 실행 스레드를 로그로 남긴다. 발표 중에는 이 로그를 띄워두는 것이 핵심이다.

```
[getBalances] start   size=2 thread=Thread[#58,open-banking-1,5,main]
```

스레드 이름 접두어로 어느 풀에서 도는지 바로 보인다.

| 접두어 | 의미 |
|---|---|
| `http-nio-8080-exec-N` | 톰캣 워커 |
| `home-info-N` | 개인화 DB 조회 풀 |
| `open-banking-N` | 오픈뱅킹 호출 풀 |
| `user-log-N` | 접속 기록 적재 풀 |

## 전체 관통 메시지

README 의 결론을 다시 적어둔다. 각 단계는 이 문장으로 수렴해야 한다.

> blocking MVC 환경에서도 코루틴은 여전히 유효하다. 코드가 간결해지고, 구조적 동시성을 쉽게 확보할 수 있다.

Virtual Thread 는 코루틴을 대체한 게 아니라, **코루틴이 필요했던 이유 중 하나(값싼 실행)** 를 제거했다.
남은 이유(수명·실패·취소의 구조화)는 그대로다.
