-- 실행 직후 불변식(Task-chat-read 8절 H4). 시드 범위(room_id > 1,000,000)만 본다. 모든 열이 0이어야 한다.
--   psql -d dance_db -f seed/invariants.sql
SELECT
  -- 방마다 seq 1..last_seq: 메시지 수 = last_seq, 최소 1, 최대 last_seq(고유 제약으로 중복은 없음 → 개수로 빈틈 확인)
  (SELECT count(*) FROM chat_room r
     LEFT JOIN (SELECT room_id, count(*) n, min(seq) lo, max(seq) hi FROM message GROUP BY room_id) m USING (room_id)
    WHERE r.room_id > 1000000
      AND (coalesce(m.n, 0) <> r.last_seq OR (r.last_seq > 0 AND (m.lo <> 1 OR m.hi <> r.last_seq)))) AS rooms_with_gap,
  (SELECT count(*) FROM (SELECT room_id, seq FROM message WHERE room_id > 1000000 GROUP BY 1, 2 HAVING count(*) > 1) d) AS duplicate_seq,
  (SELECT count(*) FROM chat_room_member cm JOIN chat_room r USING (room_id)
    WHERE r.room_id > 1000000 AND cm.last_read_seq > r.last_seq) AS read_beyond_last,
  (SELECT count(*) FROM message WHERE room_id > 1000000) AS messages_total_info;
