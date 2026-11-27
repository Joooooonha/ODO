package com.odo.performance.domain.event;

/**
 * 명단의 한 사람이 디렉터로 지정됐다. 실제로 바뀌었을 때만 발행한다(이미 디렉터면 발행하지 않는다).
 * 같은 트랜잭션 안에서 동기로 발행한다.
 */
public record PerformanceDirectorDesignatedEvent(
		long teamId,
		long performanceId,
		long actorAccountId,
		long accountId) {

}
