package com.odo.team.infrastructure;

import com.odo.team.domain.entity.TeamMember;
import com.odo.team.domain.repository.TeamMemberRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * TeamMemberRepository의 JPA 구현. Spring Data가 메서드 이름으로 쿼리를 만든다.
 */
interface TeamMemberJpaRepository extends JpaRepository<TeamMember, Long>, TeamMemberRepository {

}
