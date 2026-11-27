package com.odo.performance.infrastructure;

import java.util.Optional;

import com.odo.performance.domain.entity.PerformanceMember;
import com.odo.performance.domain.repository.PerformanceMemberRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PerformanceMemberRepository의 JPA 구현. Spring Data가 메서드 이름으로 쿼리를 만든다.
 * 잠금은 어떤 SQL이 나가는지 보이도록 네이티브 쿼리로 쓴다.
 */
interface PerformanceMemberJpaRepository extends JpaRepository<PerformanceMember, Long>, PerformanceMemberRepository {

	@Override
	@Query(value = "SELECT * FROM performance_member WHERE performance_id = :performanceId AND account_id = :accountId FOR UPDATE",
			nativeQuery = true)
	Optional<PerformanceMember> lockByPerformanceIdAndAccountId(@Param("performanceId") long performanceId,
			@Param("accountId") long accountId);

}
