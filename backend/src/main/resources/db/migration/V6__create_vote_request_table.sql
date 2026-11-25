-- 참여 투표 요청. 03_Schedule_spec.md의 "선착순 투표의 순서와 동시성", "투표 처리 세부".

-- 서버 도착 시각을 찍고 바로 저장한다(접수). 작업자가 도착 + W가 지난 요청을 일정마다 도착 시각 순서로 처리한다.
-- schedule_id에는 FK를 걸지 않는다. 일정이 삭제되어도 요청이 남아야 결과 조회에서 "일정 삭제됨"을 답할 수 있다.
-- 처리한 요청은 지우지 않고 결과만 기록한다. 결과 조회와 역전 측정에 쓴다.
CREATE TABLE vote_request (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_id BIGINT      NOT NULL,
    account_id  BIGINT      NOT NULL REFERENCES account (id),
    choice      VARCHAR(3)  NOT NULL,
    -- 서버 도착 시각(µs). 순서의 기준이다
    arrived_at  TIMESTAMPTZ NOT NULL,
    -- 도착 시각을 찍은 서버의 호스트 이름(K8s에서는 파드 이름)
    received_by VARCHAR(63) NOT NULL,
    status      VARCHAR(10) NOT NULL,
    -- 무효의 이유. 무효가 아니면 null
    reason      VARCHAR(30),
    -- 이미 처리한 요청보다 이른 도착 시각으로 늦게 처리되었는지(역전)
    reordered   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 요청 테이블에 저장된 시각. arrived_at과의 차이가 W를 정하는 지연이다
    created_at  TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT ck_vote_request_choice CHECK (choice IN ('YES', 'NO')),
    CONSTRAINT ck_vote_request_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FULL', 'INVALID')),
    CONSTRAINT ck_vote_request_reason CHECK ((status = 'INVALID') = (reason IS NOT NULL)),
    CONSTRAINT ck_vote_request_processed CHECK ((status = 'PENDING') = (processed_at IS NULL))
);

-- 처리를 기다리는 요청만 담는다. 작업자가 일정마다 도착 시각 순서로 읽는다.
CREATE INDEX idx_vote_request_pending ON vote_request (schedule_id, arrived_at, id) WHERE status = 'PENDING';
