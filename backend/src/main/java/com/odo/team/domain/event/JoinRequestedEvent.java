package com.odo.team.domain.event;

/**
 * 입장 요청이 왔다. 요청과 같은 트랜잭션 안에서 동기로 발행한다. 02_TeamSpace_spec.md의 "입장 요청 상태".
 * requesterAccountId가 요청한 사람(한 사람), leaderAccountId가 그 팀의 팀장(관련된 사람)이다.
 */
public record JoinRequestedEvent(
		long teamId,
		long requestId,
		long requesterAccountId,
		long leaderAccountId) {

}
