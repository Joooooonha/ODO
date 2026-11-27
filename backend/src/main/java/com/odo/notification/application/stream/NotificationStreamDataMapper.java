package com.odo.notification.application.stream;

import com.odo.notification.application.dto.NotificationResult;

/**
 * 알림을 실시간 이벤트의 data(목록의 항목과 같은 형식)로 바꾼다. 응답 형식은 presentation이 정하므로 그쪽이 구현한다.
 */
public interface NotificationStreamDataMapper {

	Object toData(NotificationResult result);

}
