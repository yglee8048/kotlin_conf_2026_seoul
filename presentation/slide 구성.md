`./slide_01_title.png` 는 첫장 타이틀 이미지이다.
`./KotlinConf2026-cover.pptx` , `./KotlinConfExtendedSouthKorea26-Keynote.key` 는 해당 이미지를 만든 원본 템플릿 파일이다.
해당 파일과 최대한 유사한 테마로 발표 장표를 작성해야 한다.

# 1.
첫 장은 ./slide_01_title.png 사진으로 꽉 채운다. 첫 장 타이틀 사진이다.

# 2.
두 번째 장은 연사 소개다. 아래와 같은 내용을 넣는다.
```markdown
# 연사 소개
(좌측)
이미지 (./profile_image.png)

(우측)
이영규

tigger
카카오뱅크 AI서버

홈, 수신
로그인, 얼굴인증
```

# 3. 
세 번째 장은 주제를 소개하는 장이다.
```markdown
# 백엔드 개발에 이제 코루틴은 필요 없을까?
no...!
```

# 4.
네 번째 장은 목차를 소개하는 장이다.
```markdown
# 목차
- Kotlin Bank 홈 화면 개선하기
  - Spring MVC — 비동기·병렬 처리
  - Coroutine — 구조적 동시성
  - Virtual Thread — 값싼 실행
  - Virtual Thread + Coroutine — 함께 쓰기
- 번외: non-blocking 환경에서도 Coroutine 이 유효한 이유
- Conclusion — 그래서 언제 무엇을 쓸까?
```

# 5.
다섯번째 장은 kotlin bank 홈 화면을 설명하는 장이다.
kotlin bank 홈 화면을 이미지로 보여주어 구현해야 할 결과물을 보여준다.
```markdown
# 당신은 Kotlin Bank 의 홈 화면 서버 개발자가 되었다!
> kotlin bank 홈 화면 이미지
> 핸드폰 앱 화면을 형상화
> 좌측 상단에 kotlin bank 라고 써있음
> 밑에는 좌측에 동그란 아이콘 이미지를 가지고 있고 각각 별칭과 잔액, 색상을 가진 계좌 카드가 여러개 있음
> 색상은 파스텔톤
```

# 6.
여섯번째 장은 구현을 위한 설명이다.
```markdown
# 당신은 Kotlin Bank 의 홈 화면 서버 개발자가 되었다!
- 코어뱅킹 서버: 계좌 목록, 잔액 조회 (-> 외부 서버 호출)
- 부가정보 DB: 계좌 별칭, 색상, 아이콘 조회 (-> DB 조회)
- 오픈뱅킹 서버: 오픈뱅킹 잔액 조회 (-> 외부 서버 조회)
- 유저 활동 DB: 유저 접속 기록 적재 (-> DB 저장)
```
홈 화면 구현을 위해 해야 하는 것과 그걸 하기 위해 어디를 어떻게 호출해야하는지 설명한다.

# 7.
여기서부터는 STEP 1 구현 코드를 보여준다. `getHomeItems`
코드가 화면에 잘 보이도록 적당히 몇 개의 슬라이드에 나눠서 코드를 보여준다.
```markdown
# 이제 개발해보자!
(코드)
```

# 8.
동시성의 필요성을 어필하는 장이다.
```markdown
# 일반적으로는 괜찮지만...
- 코어뱅킹 서버: 계좌 목록, 잔액 조회 (-> 외부 서버 호출) = io 대기
- 부가정보 DB: 계좌 별칭, 색상, 아이콘 조회 (-> DB 조회) = io 대기
- 오픈뱅킹 서버: 오픈뱅킹 잔액 조회 (-> 외부 서버 조회) = io 대기
- 유저 활동 DB: 유저 접속 기록 적재 (-> DB 저장) = io 대기
```
모든 호출이 io 대기 시간이 있어 비효율적임을 보여준다.

# 9.
STEP 2 구현을 계획하는 장이다.
STEP 1 구현의 코드 중 병렬로 쏠만한 지점과 비동기로 쏠만한 지점을 다시 보여준다.
```markdown
# 여기를 고치면...?
(코드)
```

# 10.
여기서부터는 STEP 2 구현 코드를 부여준다. `getHomeItemsV2`
코드가 화면에 잘 보이도록 적당히 몇 개의 슬라이드에 나눠서 코드를 보여준다.
```markdown
# 동시성을 적용해보자
(코드)
```

# 11.
STEP 2 도입으로 응답시간이 개선되었음을 보여준다.
STEP 1과 비교한다.
```markdown
# 응답시간이 개선되었다!
(응답시간 비교하는 차트)
```

# 12.
thread local 전파가 필요함을 문제제기하는 장이다.
```markdown
# thread local 은 어떻게 되지?
하지만 문제가 하나 더 있다
thread local 을 전파해야 한다
(thread local 객체 설명)
```

# 13.
STEP 3 구현 코드를 보여준다.
코드가 화면에 잘 보이도록 적당히 몇 개의 슬라이드에 나눠서 코드를 보여준다.
thread local 관련 필터를 먼저 보여주고 task decorator 부분을 보여준 뒤, 나머지 코드를 순서대로 보여준다.
```markdown
# thread local 전파하기
(코드)
```

# 14.
응답시간은 줄어들었지만 쓰레드 사용이 오히려 늘어났음을 지적하는 장이다.
STEP 4 구현을 위한 빌드업이다.
```markdown
# 응답 시간은 개선되었지만...
응답 시간은 개선되었지만 쓰레드 사용은 오히려 늘어났다
점유시간은 줄어들었으니 실질 사용을 고려하면
(시간 * 개수 해서 비교하는 차트)
```

# 15.
STEP 4 코드를 보여준다.
코드가 화면에 잘 보이도록 적당히 몇 개의 슬라이드에 나눠서 코드를 보여준다.
```markdown
# 쓰레드 조금 더 효율적으로 사용하기
(코드)
```

# 16.
코루틴을 도입해보자는 의견을 제시하는 장이다.
코루틴의 경량 쓰레드를 포함해 강점을 간략히 소개한다.
```markdown
# 코루틴을 도입한다면 어떻게 될까?
(코루틴 강점 소개)
```

# 16-1.
CPU 부터 OS 와 JVM·Kotlin 영역을 거쳐 coroutine 까지 연결되는 스케줄링 계층을 그림으로 보여준다.
커널 스레드와 platform worker 는 같은 개수로 나란히 표현하고, coroutine 은 더 많은 개수로 보여준다.
suspension 지점에서만 worker 를 놓고 일반 blocking 호출은 worker 를 점유한다는 차이를 함께 설명한다.
```markdown
# CPU 에서 coroutine 까지
CPU → [OS 스케줄러 → 커널 스레드] → JNI → [CoroutineDispatcher → platform worker → coroutine]
```

# 17.
STEP 5 코드를 보여준다.
blocking 호출은 처음부터 Dispatchers.IO 에서 실행하고, dispatcher 없이 실행했을 때의 차이는 말로만 소개한다.
코드가 화면에 잘 보이도록 적당히 몇 개의 슬라이드에 나눠서 코드를 보여준다.
```markdown
# 코루틴을 도입해보자
(runBlocking(Dispatchers.IO) 코드)
```

# 18.
기존 코드와 뭐가 달라졌는지 설명하는 장이다.
특히 쓰레드를 어떻게 사용하는지 이미지를 통해 직관적으로 보여준다.
```markdown
# 뭐가 좋아진 걸까
(차이를 설명하는 이미지)
```

# 19.
번외 장으로 executor.asDispatcher 를 쓰면 경량 쓰레드가 아니라 쓰레드 풀을 그대로 쓰는 것임을 잠깐 소개한다.
```markdown
# Executor.asDispatcher
(기존 executor 에 asDispatcher 하는 코드)
```

# 20.
코루틴에서 기존 쓰레드 풀처럼 개수를 제한하고 싶으면 어떻게 하는지 보여준다.
parallelism 코드를 보여주며 executor 에서 run, abort, discard 하는 정책과 비교해서 보여준다.
```markdown
# parallelism
```

# 21.
STEP 4 코드를 다시 보여주며, 코루틴에서도 톰캣 쓰레드를 빠르게 반납할 수 있음을 STEP 6 코드를 통해 보여준다.
```markdown
# 톰캣 쓰레드를 좀 더 효율적으로 쓰려면..?
(코드)
```

# 22.
STEP 7 코드를 통해 더 효율적으로 thread local 을 전파할 수 있음을 보여준다.
```markdown
# spring boot 4 에서 thread local 전파하기
(자동 전파를 위한 설정 코드)
```

# 22-1.
서비스 코드에 `MDCContext()`나 `CallContextElement()`를 직접 주입하지 않아도 thread local이 전파됨을 코드와 로그로 보여준다.
```markdown
# 직접 주입하지 않아도 전파된다
(직접 주입 없는 코드 + 전파된 로그)
```

# 23.
다만 자식 coroutine 에만 전파됨을 간단한 예시 코드로 설명한다.
완전한 자동은 아니며 별도 coroutine 을 시작하면 수동 전파해야함을 알려준다.

# 23.
virtual thread 를 소개하는 장이다.
virtual thread 의 특장점을 소개한다.
```markdown
# 그리고 등장하는 virtual thread
(virtual thread 소개)
```

# 23-1.
CPU 부터 OS 와 JVM 의 스케줄링 계층을 거쳐 virtual thread 까지 연결되는 구조를 그림으로 보여준다.
OS 영역의 스케줄러·커널 스레드와 JVM 영역의 ForkJoinPool 스케줄러·carrier thread·virtual thread 관계를 구분한다.
커널 스레드와 carrier thread 는 같은 개수로 나란히 보여주고, virtual thread 는 더 많은 개수로 표현해 다중화 구조를 시각화한다.
```markdown
# CPU 에서 virtual thread 까지
CPU → [OS 스케줄러 → 커널 스레드] → JNI → [ForkJoinPool 스케줄러 → 플랫폼 스레드(carrier) → virtual thread]
```

# 23-2.
Coroutine 은 `suspend` 호출점에서 중단되는데 Virtual Thread 는 언제 중단을 인지하는지 설명한다.
JVM 이 임의의 blocking 을 사후 감지하는 것이 아니라, Virtual Thread 를 지원하는 JDK blocking API 구현이 park 경로로 협력한다는 점을 강조한다.
```markdown
# suspend 가 없는데, 언제 멈출까?
RUNNING → JDK blocking 지점 → park + unmount → 이벤트 준비 → unpark + remount
```

# 24.
STEP 8 코드를 다시 보여준다. 사실상 virtual thread 설정을 보여준 뒤 STEP 1 코드를 다시 보여주는 것과 같다.
```markdown
# virtual thread 도입하기
(코드)
```

# 25.
기존 코드와 뭐가 달라졌는지 설명하는 장이다.
특히 쓰레드를 어떻게 사용하는지 이미지를 통해 직관적으로 보여준다.
처음 기본 mvc 스택을 썼을 때와 coroutine 을 썼을 때, virtual thread 를 썼을 때를 각각 비교한다.
이미지를 통해 직관적으로 보이게끔 하는 것이 중요하다.
캐리어 쓰레드가 어떻게 할당되는지 등을 보여준다.

# 26.
STEP 9를 구현하는 코드를 보여준다.

# 27.
STEP 9의 동작 방식을 다시 이미지로 설명한다.
캐리어 쓰레드와 디스패처가 어떻게 유기적으로 동작하는지 보여주는 것이 핵심이다.
이번 발표에서 가장 복잡하면서 가장 중요한 파트이다.

# 28.
STEP 10을 구현하는 코드를 보여준다.

# 29.
STEP 10의 동작 방식을 다시 이미지로 설명한다.
STEP 9와 뭐가 다른지 비교해서 설명하는 것이 핵심이다.

# 30.
virtual thread 를 썼을 때 어떤 것이 위험할 수 있는지 소개한다.
너무 많은 요청이 몰려왔을 때 제어하지 않으면 위험할 수 있음을 알려준다.

# 31.
spring boot 4 에서 등장한 concurrency limit 을 소개한다.
virtual thread 와 함께 썼을 때 효과적임을 알려준다.
외부 blocking 환경의 타임아웃 설정이나 pool 설정 등도 더욱 중요해짐을 설명한다.

# 32.
STEP 11 코드를 보여준다. 31 파트에서 소개한 내용을 실제 구현으로 보여준다.

# 32.
STEP 12 코드를 보여준다.
preview 로 virtual thread 만으로 비동기, 병렬 호출 구현하는 걸 보여준다.
다만 아직 코루틴에 비해 부족한 부분들이 많으므로 그런 부분들을 조명한다.
결과가 필요한 `async`는 `fork(Callable)`에, 결과가 필요 없는 구조화된 `launch`는 `fork(Runnable)`에 대응됨을 함께 보여준다.
스코프 밖 fire-and-forget인 `applicationCoroutineScope.launch`는 StructuredTaskScope에 직접 대응물이 없다는 차이도 설명한다.

StructuredTaskScope에서 MDC를 전파하려면 STEP 7의 accessor를 이용해 owner 스레드에서 `ContextSnapshot`을 캡처하고,
각 `Callable`을 `snapshot.wrap(...)`으로 감싸야 함을 별도 장표로 보여준다.
ScopedValue는 subtask에 자동 상속되지만 SLF4J MDC와는 별도 bridge가 필요하다는 점도 설명한다.

# 33.
기타 팁으로 transactional 사용 주의해야 함을 보여준다.

# 34.
기타 팁으로 coroutine scope 을 별도 정의할 경우 bean 이나 싱글턴으로 만들고 `@PreDestory` 를 정의하는 것이 좋음을 설명한다.

# 35.
전체 내용을 다시 요약한다.
특히 동작 원리 이미지 부분을 다시 보여주며 실제 쓰레드가 어떻게 할당되고 동작하는지를 다시 리뷰한다.

# 36.
번외 첨가 느낌으로 non-blocking 사이드에서 coroutine + spring 조합이 얼마나 강력한지 소개한다.
context 전파를 자동으로 해주는 것과 코드의 간결성을 집중적으로 보여준다.
webflux 는 원래 코드가 너무 복잡해지는 것이 문제였으나 coroutine 을 도입하면 훨씬 간결하게 쓸 수 있다.
context 전파가 자동으로 어떻게 되는지도 원리를 소개해준다.

# 36-1.
가상 스레드 위에서 동시 호출을 구현하는 세 가지 최종 선택지를 비교한다.
- VT + Coroutine (VT dispatcher)
- VT + CompletableFuture (VT executor)
- VT + StructuredTaskScope (JVM preview)

작업 표현, 구조적 동시성, 컨텍스트 전파, API 상태, 적합한 도입 상황을 같은 표에서 보여준다.

# 36-2.
"이제 Java + Spring이 아니라 Kotlin + Spring"이라는 관점 전환을 보여준다.
JetBrains와 Spring의 전략적 파트너십, Spring Boot 4 / Framework 7의 Kotlin 2.2 baseline, JSpecify null-safety, coroutine context 자동 전파를 소개한다.
Spring MVC 네이티브 coroutine과 Virtual Thread dispatcher는 2026 탐색 로드맵이며 확정 기능이 아님을 구분한다.

# 37.
최종 결론을 보여준다.
```markdown
# 그래서 어떻게 하라고?
- 일반적인 경우 => 단순하게 spring mvc 만 적용해도 충분
- ㄴ blocking IO 작업이 많고 더 효율적일 필요가 있다 => spring mvc + virtual thread
- ㄴ 빠른 응답 등을 위해 비동기나 동시 호출 등을 적용하고 싶다 => spring mvc + virtual thread + coroutines
- ㄴ io 작업이 많고 주변 환경을 non-blocking 환경으로 적용할 수 있다 => spring webflux + coroutines + r2dbc + webclient
- ㄴ streaming / sse / web socket 같은 long-lived response 가 필요하다 => spring webflux + coroutines + kotlin flow
```

# 38.
마지막으로 구조적 동시성이 어떻게 유의미한지 더 구체적인 실제 사례를 보고 싶으면 아래의 발표를 참고하라고 공유해준다.
KotlinConf2026: How google.com/search builds on Kotlin coroutines for highly scalable, streaming, concurrent servers
https://youtu.be/6D1yV5o4CWo?si=OxW9ZtTAsjFHVLRR

# 39.
Q&A 장표를 마지막으로 마무리한다.
