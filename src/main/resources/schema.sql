-- 실전 팁(비동기 + @Transactional) 예제 전용 스키마.
-- 임베디드 H2 라서 Boot 가 기동할 때마다 실행한다.

DROP TABLE IF EXISTS point_history;
DROP TABLE IF EXISTS point_account;

CREATE TABLE point_account
(
    user_id VARCHAR(50) PRIMARY KEY,
    balance BIGINT NOT NULL
);

CREATE TABLE point_history
(
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50)  NOT NULL,
    amount  BIGINT       NOT NULL,
    memo    VARCHAR(200) NOT NULL
);

INSERT INTO point_account (user_id, balance)
VALUES ('user-1', 0),
       ('user-2', 0);
