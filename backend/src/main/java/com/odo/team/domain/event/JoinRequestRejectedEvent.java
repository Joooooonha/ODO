package com.odo.team.domain.event;

/**
 * 입장 요청이 거절됐다. 거절과 같은 트랜잭션 안에서 동기로 발행한다.
 * leaderAccountId가 거절한 팀장(한 사람), requesterAccountId가 요청한 사람(관련된 사람)이다.
 */
public record JoinRequestRejectedEvent(
		long teamId,
		long requestId,
		long leaderAccountId,
		long requesterAccountId) {

}
