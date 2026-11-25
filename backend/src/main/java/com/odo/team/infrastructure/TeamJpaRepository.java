package com.odo.team.infrastructure;

import com.odo.team.domain.entity.Team;
import com.odo.team.domain.repository.TeamRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * TeamRepository의 JPA 구현. Spring Data가 메서드 이름으로 쿼리를 만든다.
 */
interface TeamJpaRepository extends JpaRepository<Team, Long>, TeamRepository {

	/** 어떤 잠금이 걸리는지 코드에서 바로 보이도록 네이티브 쿼리로 쓴다. */
	@Override
	@Query(value = "SELECT id FROM team WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
	Long lockById(@Param("id") long id);

}
