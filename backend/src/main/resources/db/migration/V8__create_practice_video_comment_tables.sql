-- 연습 영상의 시청 기록, 시점 스레드, 코멘트. 08_PracticeVideo_spec.md의 "데이터".
-- 영상 행을 지우면 모두 함께 지워진다(ON DELETE CASCADE).

-- 시청 기록. 대상자가 영상 화면에 처음 들어온 시각이다. 다시 들어와도 처음 시각을 둔다(INSERT ... ON CONFLICT DO NOTHING).
-- 기본 키가 시청 현황과 "내가 봤는지" 확인의 인덱스다.
CREATE TABLE practice_video_view (
    video_id   BIGINT      NOT NULL REFERENCES practice_video (id) ON DELETE CASCADE,
    account_id BIGINT      NOT NULL REFERENCES account (id),
    viewed_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_practice_video_view PRIMARY KEY (video_id, account_id)
);

-- 시점 스레드. 영상의 한 시점(초)에 붙는다. 같은 초에 여러 개 있을 수 있다.
-- 영상 길이를 서버가 모르므로 시점의 상한은 두지 않는다.
CREATE TABLE practice_video_thread (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    video_id              BIGINT      NOT NULL REFERENCES practice_video (id) ON DELETE CASCADE,
    position_seconds      INTEGER     NOT NULL,
    created_by_account_id BIGINT      NOT NULL REFERENCES account (id),
    created_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_practice_video_thread_position CHECK (position_seconds >= 0),
    -- 코멘트의 복합 FK 대상
    CONSTRAINT uk_practice_video_thread_id_video UNIQUE (id, video_id)
);

-- 코멘트 목록의 스레드 정렬(시점 순, 같으면 만든 순서) 그대로다.
CREATE INDEX idx_practice_video_thread_video_position ON practice_video_thread (video_id, position_seconds, id);

-- 코멘트. 스레드에 붙는다. (thread_id, video_id)가 스레드의 (id, video_id)를 가리켜, 코멘트의 영상이 스레드의 영상과 어긋나지 않는다.
-- 길이는 보이는 글자(확장 문자소)로 앱에서 센다. 이모지가 많으면 코드 포인트가 300을 넘으므로 TEXT다.
CREATE TABLE practice_video_comment (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    thread_id         BIGINT      NOT NULL,
    video_id          BIGINT      NOT NULL,
    author_account_id BIGINT      NOT NULL REFERENCES account (id),
    content           TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_practice_video_comment_thread FOREIGN KEY (thread_id, video_id)
        REFERENCES practice_video_thread (id, video_id) ON DELETE CASCADE
);

-- 영상 화면에서 그 영상의 코멘트를 스레드와 조인하지 않고 한 번에 읽는다.
CREATE INDEX idx_practice_video_comment_video_thread ON practice_video_comment (video_id, thread_id, id);
