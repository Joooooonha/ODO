package com.odo.team.infrastructure;

import java.time.Instant;

import com.odo.team.domain.JoinRequest;
import com.odo.team.domain.JoinRequestRepository;
import com.odo.team.domain.JoinRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JoinRequestRepository의 JPA 구현. Spring Data가 메서드 이름으로 쿼리를 만든다.
 */
interface JoinRequestJpaRepository extends JpaRepository<JoinRequest, Long>, JoinRequestRepository {

	/**
	 * 영속성 컨텍스트를 거치지 않고 DB에 바로 UPDATE한다.
	 * 이미 읽어 둔 JoinRequest 엔티티의 상태는 바뀌지 않으므로, 호출한 뒤에는 그 엔티티의 status를 믿지 않는다.
	 */
	@Override
	@Modifying
	@Query("""
			update JoinRequest r
			set r.status = :status, r.decidedAt = :decidedAt
			where r.id = :id and r.status = com.odo.team.domain.JoinRequestStatus.PENDING
			""")
	int changeStatusIfPending(@Param("id") Long id, @Param("status") JoinRequestStatus status,
			@Param("decidedAt") Instant decidedAt);

}
