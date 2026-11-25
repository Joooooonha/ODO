package com.odo.schedule.infrastructure;

import java.time.Instant;
import java.util.List;

import com.odo.schedule.domain.entity.VoteRequest;
import com.odo.schedule.domain.repository.VoteRequestRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * VoteRequestRepository의 JPA 구현. 대기 요청은 부분 인덱스(idx_vote_request_pending)로 찾는다.
 */
interface VoteRequestJpaRepository extends JpaRepository<VoteRequest, Long>, VoteRequestRepository {

	@Override
	@Query(value = """
			SELECT schedule_id FROM vote_request
			WHERE status = 'PENDING' AND arrived_at <= :cutoff
			GROUP BY schedule_id
			ORDER BY min(arrived_at), schedule_id
			LIMIT :limit
			""", nativeQuery = true)
	List<Long> findDueScheduleIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

	@Override
	@Query("""
			select r from VoteRequest r
			where r.scheduleId = :scheduleId
				and r.status = com.odo.schedule.domain.entity.VoteRequestStatus.PENDING
				and r.arrivedAt <= :cutoff
			order by r.arrivedAt, r.id
			""")
	List<VoteRequest> findDue(@Param("scheduleId") long scheduleId, @Param("cutoff") Instant cutoff);

	@Override
	@Query(value = """
			SELECT * FROM vote_request
			WHERE schedule_id = :scheduleId AND status = 'PENDING' AND arrived_at <= :cutoff
			ORDER BY arrived_at, id
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<VoteRequest> lockDueSkipLocked(@Param("scheduleId") long scheduleId, @Param("cutoff") Instant cutoff);

}
