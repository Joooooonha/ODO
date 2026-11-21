package com.odo.performance.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.odo.performance.domain.entity.Performance;

public interface PerformanceRepository {

	Performance save(Performance performance);

	Optional<Performance> findById(Long id);

	/** 내 공연 목록. 최근에 만든 것부터. */
	List<Performance> findAllByIdInOrderByCreatedAtDescIdDesc(Collection<Long> ids);

}
