/**
 * 시드와 생성기가 **같은 함수로** 계산하는 배치(누가 어느 팀·방·DM에 있고 어느 작업 프로세스에 붙는지).
 * 시드 SQL(seed/generate-sql.mjs)과 생성기·가짜 서버가 이 모듈 하나를 써서 서로 어긋나지 않는다.
 *
 * 번호: 사용자 USER_BASE+1부터, 팀·팀 방 TEAM_BASE+1부터(팀 t의 방 id = 팀 id), DM 방 DM_ROOM_BASE+1부터.
 * 운영·개발 데이터와 겹치지 않게 큰 수에서 시작한다.
 *
 * 1인 k팀(teamsPerUser = k): 사용자를 N/k명씩 "블록"으로 나누고, 팀 t는 블록 t, t+1, …, t+k-1(방 수로 나눈
 * 나머지)에서 N/k명씩 모은다 → 블록 b의 사용자는 팀 b, b-1, …, b-k+1에 속한다. k = 1이면 팀 = 블록.
 * 세션의 "주 팀"은 자기 블록과 같은 번호의 팀이고, 세션은 주 팀이 배정된 작업 프로세스에 붙는다(단계 1 C1·C7).
 */
export const USER_BASE = 1_000_000;
export const TEAM_BASE = 1_000_000;
export const DM_ROOM_BASE = 2_000_000;

export function buildTopology({ rooms, members, teamsPerUser = 1, dmPairsPerRoom = 0, watchersPerRoom = 0, activeRoomRatio = 1 }) {
  if (!Number.isInteger(rooms) || rooms < 1) throw new Error('rooms는 1 이상 정수');
  if (members % teamsPerUser !== 0) throw new Error(`members(${members})는 teamsPerUser(${teamsPerUser})로 나누어떨어져야 한다`);
  if (teamsPerUser > rooms) throw new Error('teamsPerUser가 방 수보다 클 수 없다');
  const block = members / teamsPerUser;
  const userCount = rooms * block;
  const userId = (u) => USER_BASE + u + 1;

  const teams = [];
  const userRooms = Array.from({ length: userCount }, () => []);
  const activeCount = Math.max(1, Math.round(rooms * activeRoomRatio));
  for (let t = 0; t < rooms; t++) {
    const memberIdx = [];
    for (let j = 0; j < teamsPerUser; j++) {
      const b = (t + j) % rooms;
      for (let i = 0; i < block; i++) memberIdx.push(b * block + i);
    }
    const id = TEAM_BASE + t + 1;
    // 앞에서부터 V명이 이 방을 "보고 있다". 활발한 방은 앞에서부터 activeCount개
    const team = { index: t, teamId: id, roomId: id, members: memberIdx, watchers: memberIdx.slice(0, watchersPerRoom), active: t < activeCount };
    teams.push(team);
    memberIdx.forEach((u) => userRooms[u].push(id));
  }

  // DM 배경: 팀마다 (0,1), (2,3) … 쌍. 같은 블록(주 팀) 안의 두 사람이라 같은 작업 프로세스에 있다
  const dms = [];
  for (const team of teams) {
    for (let p = 0; p < dmPairsPerRoom && 2 * p + 1 < block; p++) {
      const a = team.index * block + 2 * p;
      const b = a + 1;
      const roomId = DM_ROOM_BASE + dms.length + 1;
      dms.push({ roomId, a, b, homeTeam: team.index });
      userRooms[a].push(roomId);
      userRooms[b].push(roomId);
    }
  }

  return { rooms, members, teamsPerUser, block, userCount, userId, teams, dms, userRooms, primaryTeam: (u) => Math.floor(u / block) };
}

/**
 * 방(주 팀) 단위로 작업 프로세스 K개에 나눈다: 팀 t → t mod K(돌아가며). 같은 방의 세션은 한 프로세스에.
 * 연속 구간으로 나누면 "활발한 방"(앞에서부터 고른다)이 모두 첫 프로세스에 몰린다(보정 실행에서 확인).
 */
export function workerOfTeam(t, rooms, workers) {
  void rooms;
  return t % workers;
}
