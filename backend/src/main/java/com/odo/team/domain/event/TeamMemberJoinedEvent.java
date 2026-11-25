package com.odo.team.domain.event;

/**
 * 입장 요청이 승인되어 팀원이 들어왔다. 승인과 같은 트랜잭션 안에서 동기로 발행한다.
 * 일정은 이 이벤트를 받아 완료되지 않은 일정에 새 팀원의 참여자 행을 넣는다(03_Schedule_spec.md의 "참여자 행과 새 팀원").
 * 받는 쪽이 실패하면 승인도 롤백된다.
 */
public record TeamMemberJoinedEvent(
		long teamId,
		long accountId) {

}
