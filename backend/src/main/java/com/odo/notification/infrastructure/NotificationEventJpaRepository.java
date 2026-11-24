package com.odo.notification.infrastructure;

import com.odo.notification.domain.entity.NotificationEvent;
import com.odo.notification.domain.repository.NotificationEventRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * NotificationEventRepository의 JPA 구현.
 */
interface NotificationEventJpaRepository extends JpaRepository<NotificationEvent, Long>, NotificationEventRepository {

}
