package com.odo.performance.infrastructure;

import com.odo.performance.domain.entity.PerformanceMember;
import com.odo.performance.domain.repository.PerformanceMemberRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PerformanceMemberRepository의 JPA 구현. Spring Data가 메서드 이름으로 쿼리를 만든다.
 */
interface PerformanceMemberJpaRepository extends JpaRepository<PerformanceMember, Long>, PerformanceMemberRepository {

}
