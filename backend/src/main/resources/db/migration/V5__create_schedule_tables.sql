-- 연습실, 일정, 일정 참여자. 03_Schedule_spec.md의 "데이터".

-- 연습실은 팀의 목록에 저장해 두고 일정이 참조한다. 삭제는 삭제 표시(deleted_at)다.
-- 그 연습실을 쓴 일정에서 정보를 계속 보여 줘야 하기 때문이다.
CREATE TABLE practice_room (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id            BIGINT       NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    name               VARCHAR(50)  NOT NULL,
    address            VARCHAR(50),
    -- 앱에서 AES-256-GCM으로 암호화한 값("v1:" + base64)
    password_encrypted TEXT,
    rental_fee         INTEGER,
    memo               VARCHAR(300),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT ck_practice_room_rental_fee CHECK (rental_fee >= 0)
);

-- 팀의 연습실 목록(삭제하지 않은 것만).
CREATE INDEX idx_practice_room_team_active ON practice_room (team_id) WHERE deleted_at IS NULL;

-- 일정. 1차는 연습(PRACTICE)만 있다. 삭제는 하드 삭제이고 참여자 행이 함께 지워진다.
-- end_at은 start_at + duration_minutes를 저장한다. 완료 판정(end_at <= 지금)과 정렬에 인덱스를 쓰기 위해서다.
CREATE TABLE schedule (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id                   BIGINT       NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    type                      VARCHAR(20)  NOT NULL,
    title                     VARCHAR(50)  NOT NULL,
    start_at                  TIMESTAMPTZ  NOT NULL,
    duration_minutes          INTEGER      NOT NULL,
    end_at                    TIMESTAMPTZ  NOT NULL,
    practice_room_id          BIGINT REFERENCES practice_room (id),
    memo                      VARCHAR(300),
    voting_enabled            BOOLEAN      NOT NULL,
    vote_deadline             TIMESTAMPTZ,
    capacity                  INTEGER,
    -- 마지막으로 처리한 투표 요청의 도착 시각. 역전 기록용(투표 처리)
    last_confirmed_arrived_at TIMESTAMPTZ,
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_schedule_duration CHECK (duration_minutes BETWEEN 30 AND 1440),
    CONSTRAINT ck_schedule_capacity_positive CHECK (capacity >= 1),
    -- 투표를 받으면 마감 기한이 있고, 받지 않으면 마감 기한과 정원이 없다
    CONSTRAINT ck_schedule_vote_deadline CHECK (voting_enabled = (vote_deadline IS NOT NULL)),
    CONSTRAINT ck_schedule_capacity_voting CHECK (voting_enabled OR capacity IS NULL)
);

-- 예정 목록(시작이 가까운 순)과 완료 목록(최근에 끝난 순).
CREATE INDEX idx_schedule_team_start ON schedule (team_id, start_at, id);
CREATE INDEX idx_schedule_team_end ON schedule (team_id, end_at, id);

-- 일정의 참여자. 일정을 만들 때와 팀원이 들어올 때 팀원마다 한 행을 만든다.
-- 사람은 account로만 가리킨다. 팀을 나가도 끝난 일정의 기록은 남아야 하기 때문이다(나가기는 1차 이후).
CREATE TABLE schedule_participant (
    schedule_id BIGINT      NOT NULL REFERENCES schedule (id) ON DELETE CASCADE,
    account_id  BIGINT      NOT NULL REFERENCES account (id),
    status      VARCHAR(10) NOT NULL,
    vote        VARCHAR(3),
    voted_at    TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (schedule_id, account_id),
    -- 선택 참여자만 투표를 가진다
    CONSTRAINT ck_schedule_participant_vote CHECK (vote IS NULL OR status = 'OPTIONAL')
);
