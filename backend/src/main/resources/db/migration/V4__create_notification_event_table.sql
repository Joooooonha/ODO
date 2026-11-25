-- 알림 이벤트 기록. 11_Notification_spec.md의 "알림 이벤트 기록".

-- 받는 사람마다 한 행이다. 알림이 생기는 변경과 같은 트랜잭션에서 쓴다.
-- target_id에는 FK를 걸지 않는다. 가리키던 자료가 지워져도 알림은 남아야 하므로, 보여 줄 값은 payload에 복사한다.
CREATE TABLE notification_event (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipient_account_id BIGINT      NOT NULL REFERENCES account (id),
    team_id              BIGINT      NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    type                 VARCHAR(50) NOT NULL,
    target_type          VARCHAR(30) NOT NULL,
    target_id            BIGINT      NOT NULL,
    payload              JSONB       NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL
);

-- 받는 사람의 알림함 조회.
CREATE INDEX idx_notification_event_recipient ON notification_event (recipient_account_id, created_at);
