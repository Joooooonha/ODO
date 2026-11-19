package com.odo.team.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.odo.team.domain.entity.JoinRequest;
import com.odo.team.domain.entity.JoinRequestStatus;

public interface JoinRequestRepository {

	JoinRequest save(JoinRequest request);

	/**
	 * 대기 중인 요청을 저장하고 바로 DB에 반영한다.
	 * 같은 사람이 같은 팀에 대기 중인 요청이 이미 있으면 부분 유니크 인덱스 위반이 여기서 바로 난다.
	 */
	JoinRequest saveAndFlush(JoinRequest request);

	Optional<JoinRequest> findById(Long id);

	/**
	 * 대기 중일 때만 상태와 처리 시각을 바꾼다(조건부 UPDATE). 바뀐 행 수(0 또는 1)를 돌려준다.
	 * 같은 요청에 승인, 거절, 취소가 동시에 와도 하나만 1을 받는다.
	 */
	int changeStatusIfPending(Long id, JoinRequestStatus status, Instant decidedAt);

	Optional<JoinRequest> findByTeamIdAndAccountIdAndStatus(Long teamId, Long accountId, JoinRequestStatus status);

	/** 팀장의 대기 목록. 오래된 것부터. */
	List<JoinRequest> findAllByTeamIdAndStatusOrderByRequestedAtAscIdAsc(Long teamId, JoinRequestStatus status);

	/** 내 요청 목록. 오래된 것부터. */
	List<JoinRequest> findAllByAccountIdAndStatusOrderByRequestedAtAscIdAsc(Long accountId, JoinRequestStatus status);

}
