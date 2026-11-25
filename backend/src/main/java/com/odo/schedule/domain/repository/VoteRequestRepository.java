package com.odo.schedule.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.odo.schedule.domain.entity.VoteRequest;

public interface VoteRequestRepository {

	VoteRequest save(VoteRequest request);

	Optional<VoteRequest> findById(Long id);

	/**
	 * 도착 시각이 cutoff(지금 − W) 이하인 대기 요청이 있는 일정. 가장 오래 기다린 일정부터 limit개까지다.
	 */
	List<Long> findDueScheduleIds(Instant cutoff, int limit);

	/**
	 * 그 일정의 대기 요청 중 도착 시각이 cutoff 이하인 것. (도착 시각, 요청 ID) 순이다.
	 * 일정 행을 잠근 뒤 읽으므로 요청 행은 잠그지 않는다.
	 */
	List<VoteRequest> findDue(long scheduleId, Instant cutoff);

	/**
	 * findDue와 같지만 요청 행을 FOR UPDATE SKIP LOCKED로 잠그고 읽는다. 일정 행이 없어(삭제된 일정) 일정 행으로
	 * 차례를 정할 수 없을 때 쓴다. 다른 작업자가 같은 요청을 함께 무효로 처리하지 않게 하기 위해서다.
	 */
	List<VoteRequest> lockDueSkipLocked(long scheduleId, Instant cutoff);

	/**
	 * 아직 처리할 때가 되지 않은(도착 시각이 cutoff보다 늦은) 대기 요청 중 가장 이른 도착 시각. 모든 일정에서 찾는다.
	 * 작업자가 다음에 언제 돌지 정할 때 쓴다.
	 */
	Optional<Instant> findEarliestUpcomingArrival(Instant cutoff);

}
