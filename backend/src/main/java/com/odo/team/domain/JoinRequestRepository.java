package com.odo.team.domain;

import java.util.List;
import java.util.Optional;

public interface JoinRequestRepository {

	JoinRequest save(JoinRequest request);

	Optional<JoinRequest> findById(Long id);

	Optional<JoinRequest> findByTeamIdAndAccountIdAndStatus(Long teamId, Long accountId, JoinRequestStatus status);

	/** 팀장의 대기 목록. 오래된 것부터. */
	List<JoinRequest> findAllByTeamIdAndStatusOrderByRequestedAtAscIdAsc(Long teamId, JoinRequestStatus status);

	/** 내 요청 목록. 오래된 것부터. */
	List<JoinRequest> findAllByAccountIdAndStatusOrderByRequestedAtAscIdAsc(Long accountId, JoinRequestStatus status);

}
