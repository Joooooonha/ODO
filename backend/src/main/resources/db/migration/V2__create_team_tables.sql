-- 팀 스페이스, 팀원, 입장 요청. 02_TeamSpace_spec.md의 "데이터".

-- 초대 코드는 팀장이 다시 봐야 하므로 원문을 저장한다. 새로 발급하면 덮어써서 이전 코드는 바로 무효가 된다.
CREATE TABLE team (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(30)  NOT NULL,
    description VARCHAR(100),
    invite_code CHAR(22)     NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_team_invite_code UNIQUE (invite_code)
);

-- 팀장은 role로 나타낸다. 나가기와 추방(1차 이후)은 행을 지운다.
CREATE TABLE team_member (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id    BIGINT      NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    account_id BIGINT      NOT NULL REFERENCES account (id),
    role       VARCHAR(10) NOT NULL,
    generation SMALLINT,
    joined_at  TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_team_member_role CHECK (role IN ('LEADER', 'MEMBER')),
    CONSTRAINT ck_team_member_generation CHECK (generation BETWEEN 1 AND 999),
    CONSTRAINT uk_team_member_team_account UNIQUE (team_id, account_id)
);

-- 팀마다 팀장은 한 명이다.
CREATE UNIQUE INDEX uk_team_member_leader ON team_member (team_id) WHERE role = 'LEADER';
-- 내 팀 목록(들어온 순서).
CREATE INDEX idx_team_member_account_joined ON team_member (account_id, joined_at);

-- 상태를 바꾼 행은 지우지 않는다. 다시 요청하면 새 행을 만든다.
CREATE TABLE join_request (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id      BIGINT      NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    account_id   BIGINT      NOT NULL REFERENCES account (id),
    generation   SMALLINT,
    status       VARCHAR(10) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    decided_at   TIMESTAMPTZ,
    CONSTRAINT ck_join_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELED')),
    CONSTRAINT ck_join_request_generation CHECK (generation BETWEEN 1 AND 999),
    CONSTRAINT ck_join_request_decided_at CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

-- 한 사람이 한 팀에 대기 중인 요청을 하나만 가진다. 동시에 두 번 요청해도 하나만 들어간다.
CREATE UNIQUE INDEX uk_join_request_pending ON join_request (team_id, account_id) WHERE status = 'PENDING';
-- 팀장의 대기 목록(오래된 것부터).
CREATE INDEX idx_join_request_team_pending ON join_request (team_id, requested_at) WHERE status = 'PENDING';
-- 내 요청 목록.
CREATE INDEX idx_join_request_account_pending ON join_request (account_id) WHERE status = 'PENDING';
