-- 쿼리 ②의 분류 검증: 코드에서 잠금을 잡는 문장 전부(pg_stat_activity.query에 보이는 모양 = JDBC가 ?를 $n으로 바꾼 문장)를
-- 원문 CASE와 수정안 CASE에 그대로 넣어 본다. Hibernate 문장은 테스트 SQL 로그(show-sql)에서 그대로 옮겼다.
WITH stmt(path, lock_taken, query) AS (VALUES
  ('메시지 저장 ① 번호(MessageWriter.NEXT_SEQ)', 'chat_room 행 FOR UPDATE',
   'UPDATE chat_room SET last_seq = last_seq + 1, modified_at = clock_timestamp() WHERE room_id = $1 RETURNING last_seq, modified_at'),
  ('메시지 저장 ③ 본문(INSERT_TEXT)', 'message 고유 인덱스(같은 clientMessageId 동시)',
   'INSERT INTO message (room_id, seq, sender_id, type, content, client_message_id, created_at) VALUES ($1, $2, $3, ''TEXT'', $4, $5, $6) ON CONFLICT (sender_id, client_message_id) DO NOTHING RETURNING message_id'),
  ('시스템 메시지(INSERT_SYSTEM)', 'message 고유 인덱스',
   'INSERT INTO message (room_id, seq, sender_id, type, content, client_message_id, created_at) VALUES ($1, $2, NULL, ''SYSTEM'', $3, $4, $5) ON CONFLICT (client_message_id) WHERE type = ''SYSTEM'' DO NOTHING RETURNING message_id'),
  ('읽음 갱신(ReadPositionWriter.ADVANCE)', 'chat_room_member 행(같은 사용자 두 기기)',
   'UPDATE chat_room_member SET last_read_seq = $1 WHERE room_id = $2 AND user_id = $3 AND status = ''ACTIVE'' AND last_read_seq < $4 AND $5 <= (SELECT last_seq FROM chat_room WHERE room_id = $6)'),
  ('7.4 참여·나가기·추방 ① TeamMemberRepository.findForUpdate', 'team_member 행 FOR NO KEY UPDATE',
   'select tm1_0.team_member_id,tm1_0.created_at,tm1_0.joined_at,tm1_0.left_at,tm1_0.modified_at,tm1_0.role,tm1_0.status,tm1_0.team_id,tm1_0.user_id from team_member tm1_0 where tm1_0.team_id=$1 and tm1_0.user_id=$2 for no key update'),
  ('7.4 참여(신규) team_member 삽입', 'team_member 고유 인덱스',
   'insert into team_member (created_at,joined_at,left_at,modified_at,role,status,team_id,user_id) values ($1,$2,$3,$4,$5,$6,$7,$8)'),
  ('7.4 재참여·나가기·추방 team_member 변경(dirty checking)', 'team_member 행',
   'update team_member set created_at=$1,joined_at=$2,left_at=$3,modified_at=$4,role=$5,status=$6,team_id=$7,user_id=$8 where team_member_id=$9'),
  ('7.4 ② ChatRoomRepository.findByTeamIdForUpdate', 'chat_room 행 FOR NO KEY UPDATE',
   'select cr1_0.room_id,cr1_0.created_at,cr1_0.dm_user_high,cr1_0.dm_user_low,cr1_0.last_seq,cr1_0.modified_at,cr1_0.team_id,cr1_0.type from chat_room cr1_0 where cr1_0.team_id=$1 for no key update'),
  ('7.4 ③ 참여 ChatRoomMemberRepository.activate', 'chat_room_member 행/고유 인덱스',
   'INSERT INTO chat_room_member (room_id, user_id, status, last_read_seq) VALUES ($1, $2, ''ACTIVE'', $3) ON CONFLICT ON CONSTRAINT uk_chat_room_member_room_user DO UPDATE SET status = ''ACTIVE'', last_read_seq = EXCLUDED.last_read_seq, modified_at = now()'),
  ('7.4 ③ 나가기·추방 ChatRoomMemberRepository.deactivate', 'chat_room_member 행',
   'UPDATE chat_room_member SET status = $1, modified_at = now() WHERE room_id = $2 AND user_id = $3 AND status = ''ACTIVE'''),
  ('팀 생성 chat_room 삽입(ChatRoomService.createTeamRoom)', 'chat_room 고유 인덱스(team_id)',
   'insert into chat_room (dm_user_high,dm_user_low,last_seq,team_id,type) values ($1,$2,$3,$4,$5)'),
  ('팀 생성 팀장 chat_room_member 삽입', 'chat_room_member 고유 인덱스',
   'insert into chat_room_member (last_read_seq,room_id,status,user_id) values ($1,$2,$3,$4)'),
  ('DM 방 만들기(ChatQueries.insertDmRoom)', 'chat_room 고유 인덱스(uk_chat_room_dm_pair)',
   'INSERT INTO chat_room (type, dm_user_low, dm_user_high) VALUES (''DM'', $1, $2) ON CONFLICT ON CONSTRAINT uk_chat_room_dm_pair DO NOTHING RETURNING room_id'),
  ('DM 멤버(ChatQueries.insertDmMember)', 'chat_room_member 고유 인덱스',
   'INSERT INTO chat_room_member (room_id, user_id, status, last_read_seq) VALUES ($1, $2, ''ACTIVE'', 0) ON CONFLICT ON CONSTRAINT uk_chat_room_member_room_user DO NOTHING')
)
SELECT path, lock_taken,
       CASE WHEN query ILIKE '%chat_room%'   THEN 'chat_room'
            WHEN query ILIKE '%chat_member%' THEN 'chat_member'
            ELSE 'other' END AS original,
       CASE WHEN query ILIKE '%chat_room_member%' THEN 'chat_room_member'
            WHEN query ILIKE '%chat_room%'        THEN 'chat_room'
            ELSE 'other' END AS revised
FROM stmt;
