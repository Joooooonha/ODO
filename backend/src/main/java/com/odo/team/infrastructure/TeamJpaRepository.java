package com.odo.team.infrastructure;

import com.odo.team.domain.entity.Team;
import com.odo.team.domain.repository.TeamRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * TeamRepository의 JPA 구현. Spring Data가 메서드 이름으로 쿼리를 만든다.
 */
interface TeamJpaRepository extends JpaRepository<Team, Long>, TeamRepository {

}
