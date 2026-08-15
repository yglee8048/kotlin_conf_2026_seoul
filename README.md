발표를 위한 데모 프로젝트

아래의 코드들을 단게적으로 발표에서 보여주는 것이 목표이다.
전달하고자 하는 최종적인 결론은 여전히 blocking mvc 환경에서도 코루틴이 유효하다는 것이다. (코드가 간결하고, 구조적 동시성을 쉽게 확보)

1. blocking mvc 환경에서 일반적인 홈 화면 조회
2. blocking mvc 환경에서 병렬 호출과 비동기 적용 (with spring async, completable future)
3. 커스텀 thread local 과 mdc 을 사용하는 경우 전파하기
4. controller 에서 deferred 객체를 응답하여 톰캣 쓰레드 더 효율적으로 사용하기
5. blocking mvc 환경에서 coroutine 적용하여 코드 더 간결하게 만들고 구조적 동시성 확보하기
6. controller 를 suspend 로 만들어서 톰켓 쓰레드 더 효율적으로 사용하기
7. spring 7 의 기능을 통해 accessor 선언해서 coroutine context 자동 전파되게 만들기
8. virtual thread 도입해서 간결한 blocking 코드 작성하기 (다시 1번 코드 + virtual 쓰레드)
9. virtual thread + coroutine 으로 효율성 확보하기 (다시 5번코드 + virtual 쓰레드)
10. virtual thread + coroutine 에서 virtual thread dispatcher 사용하기
11. virtual thread 환경에서 안정성 확보하기 (concurrency limit, timeout)
12. 번외) virtual thread 로 비동기, 병렬 호출 구현하기

각 단계 별로 각 상황에서 쓰레드가 어떻게 할당되고 반납되는지 설명할 예정이다.
그리고 각 상황의 예제 코드를 보여주며 코드의 간결성과 구조적 동시성 등을 설명하고, 실전 적용 팁 등을 알려줄 예정이다.

(기타 공유하고 싶은 실전 팁)
- 비동기 호출 + transactional 사용의 위험성 (db 쓰기 작업 2번 있는 별도 예제 만들어서 짧게 설명하기)
- coroutine scope 을 bean 으로 등록하기
  - heap 메모리를 효율적으로 사용
  - predestroy 를 통해 서버 종료 시 실행 중인 코루틴들을 취소 전파
  - 단 supervisor job 선언해서 다른 api 간 취소 전파되지 않도록하기 
