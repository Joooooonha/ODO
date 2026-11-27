package com.odo.schedule.application;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import com.odo.schedule.domain.entity.Schedule;
import com.odo.schedule.domain.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 다른 기능이 응답에 넣을 일정 값을 읽는다. 알림이 일정 제목을 볼 때 읽는 데 쓴다(11_Notification_spec.md의 "보여 줄 값").
 * 호출하는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class ScheduleReader {

	private final ScheduleRepository scheduleRepository;

	/** 여러 일정의 제목을 한 번의 쿼리(IN)로 읽는다. 지워진 일정은 결과에 없다. */
	public Map<Long, String> titlesOf(Collection<Long> scheduleIds) {
		return scheduleRepository.findAllById(scheduleIds)
			.stream()
			.collect(Collectors.toMap(Schedule::getId, Schedule::getTitle));
	}

}
