package com.odo.notification.presentation;

import com.odo.notification.application.dto.NotificationResult;
import com.odo.notification.application.stream.NotificationStreamDataMapper;
import com.odo.notification.presentation.dto.NotificationResponse;
import org.springframework.stereotype.Component;

/** 실시간 이벤트의 data를 목록의 항목(NotificationResponse)과 같은 형식으로 만든다. */
@Component
class NotificationResponseDataMapper implements NotificationStreamDataMapper {

	@Override
	public Object toData(NotificationResult result) {
		return NotificationResponse.from(result);
	}

}
