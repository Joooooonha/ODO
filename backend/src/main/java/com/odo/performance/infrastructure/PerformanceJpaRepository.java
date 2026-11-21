package com.odo.performance.infrastructure;

import com.odo.performance.domain.entity.Performance;
import com.odo.performance.domain.repository.PerformanceRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PerformanceRepository의 JPA 구현. Spring Data가 메서드 이름으로 쿼리를 만든다.
 */
interface PerformanceJpaRepository extends JpaRepository<Performance, Long>, PerformanceRepository {

}
