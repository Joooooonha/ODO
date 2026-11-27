package com.odo.performance.application;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import com.odo.performance.domain.entity.Performance;
import com.odo.performance.domain.repository.PerformanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 다른 기능이 응답에 넣을 공연 값을 읽는다. 알림이 공연 이름을 볼 때 읽는 데 쓴다(11_Notification_spec.md의 "보여 줄 값").
 * 호출하는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class PerformanceReader {

	private final PerformanceRepository performanceRepository;

	/** 여러 공연의 이름을 한 번의 쿼리(IN)로 읽는다. 없는 공연은 결과에 없다. */
	public Map<Long, String> namesOf(Collection<Long> performanceIds) {
		return performanceRepository.findAllById(performanceIds)
			.stream()
			.collect(Collectors.toMap(Performance::getId, Performance::getName));
	}

}
