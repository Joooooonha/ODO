import { readFileSync } from 'node:fs';
import { buildTopology, DM_ROOM_BASE, TEAM_BASE, USER_BASE } from '../src/topology.mjs';

/**
 * 측정 DB 시드 SQL. 사용: `node seed/generate-sql.mjs configs/<설정>.json > seed.sql`
 * → `psql -v ON_ERROR_STOP=1 -d dance_db -f seed.sql`
 *
 * 생성기와 **같은 배치 함수**(src/topology.mjs)를 쓴다. 사용자·팀·팀 멤버·팀 채팅방·채팅 멤버·DM을 SQL로 직접
 * 넣는다(API로 만들면 느리다). 번호는 운영·개발 데이터와 겹치지 않는 큰 수(USER_BASE 등)에서 시작하고,
 * 다시 넣으면 먼저 그 범위를 지운다(같은 출발점). 메시지는 넣지 않는다 — 방은 last_seq 0, 읽은 위치 0에서 시작한다.
 *
 * 로그인 방식: 측정 생성기는 JWT를 직접 만들므로 비밀번호를 두지 않는다(provider = LOCAL, password NULL).
 */
const config = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const topo = buildTopology(config.topology);
const out = [];
const sql = (s) => out.push(s);
const values = (rows) => rows.join(',\n');

sql('-- 생성: load-test/seed/generate-sql.mjs ' + JSON.stringify(config.topology));
sql('\\set ON_ERROR_STOP on');
sql('BEGIN;');
sql(`-- 이전 시드 지우기(이 범위만)
DELETE FROM chat_room_member WHERE room_id > ${TEAM_BASE} OR user_id > ${USER_BASE};
DELETE FROM message WHERE room_id > ${TEAM_BASE};
DELETE FROM chat_room WHERE room_id > ${TEAM_BASE};
DELETE FROM team_member WHERE team_id > ${TEAM_BASE};
DELETE FROM team WHERE team_id > ${TEAM_BASE};
DELETE FROM users WHERE user_id > ${USER_BASE};`);

// 사용자: generate_series 한 문장(수천 명도 빠르다)
sql(`INSERT INTO users (user_id, login_id, nickname, provider)
SELECT ${USER_BASE} + g, 'lt_' || g, 'lt' || g, 'LOCAL' FROM generate_series(1, ${topo.userCount}) AS g;`);

// 팀·팀 방: 팀 t(0부터)의 id = TEAM_BASE + t + 1, 팀장 = 그 팀 멤버 목록의 첫 사람
const CHUNK = 5000;
const chunks = (rows) => {
  const res = [];
  for (let i = 0; i < rows.length; i += CHUNK) res.push(rows.slice(i, i + CHUNK));
  return res;
};
sql(`INSERT INTO team (team_id, team_name, leader_id, join_code, created_at, modified_at)
SELECT ${TEAM_BASE} + g, '측정팀 ' || g, NULL, 'L' || lpad(g::text, 7, '0'), now(), now() FROM generate_series(1, ${topo.rooms}) AS g;`);
const leaders = topo.teams.map((t) => `(${t.teamId}, ${topo.userId(t.members[0])})`);
for (const c of chunks(leaders)) {
  sql(`UPDATE team SET leader_id = v.leader FROM (VALUES\n${values(c)}) AS v(team, leader) WHERE team.team_id = v.team;`);
}
const teamMembers = topo.teams.flatMap((t) => t.members.map((u, i) =>
  `(${t.teamId}, ${topo.userId(u)}, '${i === 0 ? 'LEADER' : 'MEMBER'}', 'ACTIVE', now(), now(), now())`));
for (const c of chunks(teamMembers)) {
  sql(`INSERT INTO team_member (team_id, user_id, role, status, joined_at, created_at, modified_at) VALUES\n${values(c)};`);
}
sql(`INSERT INTO chat_room (room_id, type, team_id, last_seq)
SELECT ${TEAM_BASE} + g, 'TEAM', ${TEAM_BASE} + g, 0 FROM generate_series(1, ${topo.rooms}) AS g;`);
const roomMembers = topo.teams.flatMap((t) => t.members.map((u) => `(${t.roomId}, ${topo.userId(u)}, 'ACTIVE', 0)`));
for (const c of chunks(roomMembers)) {
  sql(`INSERT INTO chat_room_member (room_id, user_id, status, last_read_seq) VALUES\n${values(c)};`);
}

// DM 배경
if (topo.dms.length > 0) {
  const dmRooms = topo.dms.map((d) => {
    const a = topo.userId(d.a);
    const b = topo.userId(d.b);
    return `(${d.roomId}, 'DM', ${Math.min(a, b)}, ${Math.max(a, b)}, 0)`;
  });
  for (const c of chunks(dmRooms)) {
    sql(`INSERT INTO chat_room (room_id, type, dm_user_low, dm_user_high, last_seq) VALUES\n${values(c)};`);
  }
  const dmMembers = topo.dms.flatMap((d) => [d.a, d.b].map((u) => `(${d.roomId}, ${topo.userId(u)}, 'ACTIVE', 0)`));
  for (const c of chunks(dmMembers)) {
    sql(`INSERT INTO chat_room_member (room_id, user_id, status, last_read_seq) VALUES\n${values(c)};`);
  }
}

// 자동 번호(identity)가 시드 범위와 부딪히지 않게: 시드는 큰 수에 있고, 앱이 새로 만드는 행은 원래 번호를 이어 쓴다.
// 시드 범위를 넘을 만큼 자라 있으면 멈춘다(겹치면 안 된다)
sql(`DO $$
BEGIN
  IF (SELECT coalesce(max(user_id), 0) FROM users WHERE user_id <= ${USER_BASE}) >= ${USER_BASE}
     OR (SELECT coalesce(max(room_id), 0) FROM chat_room WHERE room_id <= ${TEAM_BASE}) >= ${TEAM_BASE} THEN
    RAISE EXCEPTION '기존 데이터의 번호가 시드 범위에 닿았다';
  END IF;
END $$;`);
sql('COMMIT;');
sql(`-- 확인: 사용자 ${topo.userCount}, 팀 ${topo.rooms}, 팀 멤버 ${teamMembers.length}, DM ${topo.dms.length}(방 번호 ${DM_ROOM_BASE + 1}~)`);
sql(`SELECT (SELECT count(*) FROM users WHERE user_id > ${USER_BASE}) AS users,
       (SELECT count(*) FROM chat_room WHERE room_id > ${TEAM_BASE}) AS rooms,
       (SELECT count(*) FROM chat_room_member WHERE room_id > ${TEAM_BASE}) AS chat_members;`);
process.stdout.write(out.join('\n\n') + '\n');
