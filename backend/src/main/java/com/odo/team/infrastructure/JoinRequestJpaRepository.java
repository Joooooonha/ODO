package com.odo.team.infrastructure;

import com.odo.team.domain.JoinRequest;
import com.odo.team.domain.JoinRequestRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JoinRequestRepository의 JPA 구현. Spring Data가 메서드 이름으로 쿼리를 만든다.
 */
interface JoinRequestJpaRepository extends JpaRepository<JoinRequest, Long>, JoinRequestRepository {

}
