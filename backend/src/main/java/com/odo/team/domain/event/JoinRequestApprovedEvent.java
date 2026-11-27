package com.odo.team.domain.event;

/**
 * 입장 요청이 승인됐다. TeamMemberJoinedEvent(팀원이 생김)와 따로 발행한다. 승인과 같은 트랜잭션 안에서 동기로 발행한다.
 * leaderAccountId가 승인한 팀장(한 사람), requesterAccountId가 요청한 사람(관련된 사람)이다.
 */
public record JoinRequestApprovedEvent(
		long teamId,
		long requestId,
		long leaderAccountId,
		long requesterAccountId) {

}
