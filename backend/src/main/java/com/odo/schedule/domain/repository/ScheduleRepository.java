package com.odo.schedule.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.odo.schedule.domain.entity.Schedule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ScheduleRepository {

	Schedule save(Schedule schedule);

	Optional<Schedule> findById(Long id);

	void delete(Schedule schedule);

	/**
	 * 일정 행을 FOR UPDATE로 잠그고 읽는다. 팀장의 수정, 삭제, 참여자 상태 변경과 투표 처리(작업자)가 같은 잠금을 써서
	 * 정원 확인과 정원 변경, 투표 반영과 일정 삭제가 서로 끼어들지 않는다.
	 */
	Optional<Schedule> lockById(long id);

	/**
	 * 팀의 완료되지 않은 일정을 FOR SHARE로 잠그고 읽는다. 새 팀원의 참여자 행을 넣을 때 쓴다.
	 * 그사이 일정이 지워지면 삭제가 이 잠금을 기다리므로, 지워진 일정에 행을 넣다가 FK 오류가 나지 않는다.
	 */
	List<Schedule> lockIncompleteForShare(long teamId, Instant now);

	/** 예정: 종료 전(진행 중 포함), 시작이 가까운 순. */
	Page<Schedule> findByTeamIdAndEndAtAfterOrderByStartAtAscIdAsc(Long teamId, Instant now, Pageable pageable);

	/** 완료: 종료 시간이 지남, 최근에 끝난 순. */
	Page<Schedule> findByTeamIdAndEndAtLessThanEqualOrderByEndAtDescIdDesc(Long teamId, Instant now,
			Pageable pageable);

}
