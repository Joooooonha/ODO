-- 공연, 공연 명단, 곡. 06_Performance_spec.md의 "데이터".

-- 공연은 팀 안에 있다. 팀을 지우면(1차 이후) 공연, 명단, 곡이 함께 지워진다.
CREATE TABLE performance (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id     BIGINT       NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(100),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    -- 명단 행이 공연과 같은 팀을 가리키게 하는 복합 FK의 대상
    CONSTRAINT uk_performance_id_team UNIQUE (id, team_id)
);

-- 팀의 공연 조회.
CREATE INDEX idx_performance_team ON performance (team_id);

-- 공연 안의 역할은 DIRECTOR, MEMBER다. 팀장인지는 team_member.role로 본다.
CREATE TABLE performance_member (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    performance_id BIGINT      NOT NULL,
    team_id        BIGINT      NOT NULL,
    account_id     BIGINT      NOT NULL,
    role           VARCHAR(10) NOT NULL,
    joined_at      TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_performance_member_role CHECK (role IN ('DIRECTOR', 'MEMBER')),
    -- 명단의 팀은 공연의 팀과 같다. 이것이 없으면 아래 팀원 FK가 다른 팀의 팀원을 허용한다.
    CONSTRAINT fk_performance_member_performance FOREIGN KEY (performance_id, team_id)
        REFERENCES performance (id, team_id) ON DELETE CASCADE,
    -- 명단에는 그 팀의 팀원만 들어간다. 팀 나가기와 추방(1차 이후)은 명단 행을 먼저 지운다.
    CONSTRAINT fk_performance_member_team_member FOREIGN KEY (team_id, account_id)
        REFERENCES team_member (team_id, account_id),
    -- 한 사람은 한 공연 명단에 한 번만 들어간다. 요청마다 하는 명단 확인도 이 인덱스를 쓴다.
    CONSTRAINT uk_performance_member_performance_account UNIQUE (performance_id, account_id)
);

-- 내 공연 목록(그 팀에서 내가 명단에 있는 공연).
CREATE INDEX idx_performance_member_team_account ON performance_member (team_id, account_id);

-- 삭제는 행을 지우지 않고 deleted_at을 적는다. 삭제한 곡의 이름이 숙제와 연습 영상(1차 이후)에 남아야 한다.
-- 담당 디렉터는 계정을 가리킨다. 명단에서 빠져도(1차 이후) 곡 행은 남는다.
CREATE TABLE song (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    performance_id      BIGINT       NOT NULL REFERENCES performance (id) ON DELETE CASCADE,
    director_account_id BIGINT       NOT NULL REFERENCES account (id),
    name                VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    deleted_at          TIMESTAMPTZ
);

-- 곡 목록(만든 순서). 삭제하지 않은 곡만.
CREATE INDEX idx_song_performance_active ON song (performance_id, id) WHERE deleted_at IS NULL;
