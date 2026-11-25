package com.odo.schedule.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.odo.schedule.domain.entity.Schedule;
import com.odo.schedule.domain.repository.ScheduleRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * ScheduleRepository의 JPA 구현. 잠금은 어떤 잠금이 걸리는지 보이도록 네이티브 쿼리로 쓴다.
 */
interface ScheduleJpaRepository extends JpaRepository<Schedule, Long>, ScheduleRepository {

	@Override
	@Query(value = "SELECT * FROM schedule WHERE id = :id FOR UPDATE", nativeQuery = true)
	Optional<Schedule> lockById(@Param("id") long id);

	@Override
	@Query(value = "SELECT * FROM schedule WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
	Optional<Schedule> lockByIdSkipLocked(@Param("id") long id);

	@Override
	@Query(value = "SELECT * FROM schedule WHERE team_id = :teamId AND end_at > :now ORDER BY id FOR SHARE",
			nativeQuery = true)
	List<Schedule> lockIncompleteForShare(@Param("teamId") long teamId, @Param("now") Instant now);

}
