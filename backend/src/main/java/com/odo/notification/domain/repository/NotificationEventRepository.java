package com.odo.notification.domain.repository;

import com.odo.notification.domain.entity.NotificationEvent;

public interface NotificationEventRepository {

	NotificationEvent save(NotificationEvent event);

}
