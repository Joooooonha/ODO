-- 알림함. 11_Notification_spec.md의 "데이터".

-- V4의 notification_event는 받는 사람마다 한 행이고 사용자가 지우는, 알림함의 항목이다. 역할에 맞게 이름을 바꾼다.
ALTER TABLE notification_event RENAME TO notification;
ALTER TABLE notification RENAME CONSTRAINT notification_event_pkey TO notification_pkey;
ALTER TABLE notification RENAME CONSTRAINT notification_event_recipient_account_id_fkey TO notification_recipient_account_id_fkey;
ALTER TABLE notification RENAME CONSTRAINT notification_event_team_id_fkey TO notification_team_id_fkey;
ALTER SEQUENCE notification_event_id_seq RENAME TO notification_id_seq;

-- seq: 받는 사람의 순번. 알림함 상태 행을 잠그고 올리므로 한 사람 안에서 커밋 순서와 같다.
-- actor_account_id: 그 일을 한 사람. 이름은 볼 때 읽는다.
-- 배포 전이라 기존 행이 없다고 보고 기본값 없이 NOT NULL로 더한다.
ALTER TABLE notification ADD COLUMN seq BIGINT NOT NULL;
ALTER TABLE notification ADD COLUMN actor_account_id BIGINT NOT NULL REFERENCES account (id);

-- payload에는 사건의 내용(바뀐 상태)과 삭제 알림의 제목만 남는다. 없으면 빈 객체다.
ALTER TABLE notification ALTER COLUMN payload SET DEFAULT '{}';

-- 목록(seq 최신순), 따라잡기(seq > ?), 비우기(seq <= ?)를 모두 이 인덱스로 읽는다.
DROP INDEX idx_notification_event_recipient;
ALTER TABLE notification ADD CONSTRAINT uk_notification_recipient_seq UNIQUE (recipient_account_id, seq);

-- 계정마다 한 행. 알림을 넣을 때 잠그고 last_seq를 올린다. 계정 행과 따로 두어 계정 작업과 잠금이 부딪히지 않게 한다.
-- seen_seq: 알림함을 열 때 화면이 본 맨 위 순번. 새 알림 표시에 쓴다.
CREATE TABLE notification_inbox (
    account_id BIGINT PRIMARY KEY REFERENCES account (id),
    last_seq   BIGINT NOT NULL DEFAULT 0,
    seen_seq   BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_notification_inbox_seq CHECK (seen_seq >= 0 AND seen_seq <= last_seq)
);
