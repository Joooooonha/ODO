package com.odo.schedule.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.ScheduleParticipant;
import com.odo.schedule.domain.entity.Vote;

public interface ScheduleParticipantRepository {

	ScheduleParticipant save(ScheduleParticipant participant);

	<S extends ScheduleParticipant> List<S> saveAll(Iterable<S> participants);

	Optional<ScheduleParticipant> findByScheduleIdAndAccountId(Long scheduleId, Long accountId);

	List<ScheduleParticipant> findAllByScheduleIdOrderByAccountIdAsc(Long scheduleId);

	/** 목록의 일정들에서 내 참여자 행. */
	List<ScheduleParticipant> findAllByAccountIdAndScheduleIdIn(Long accountId, Collection<Long> scheduleIds);

	long countByScheduleIdAndStatusAndVote(Long scheduleId, ParticipantStatus status, Vote vote);

	long countByScheduleIdAndStatus(Long scheduleId, ParticipantStatus status);

	/** 일정별 참여 인원(참여로 투표한 선택 참여자 수). 참여자가 없는 일정은 결과에 없다. */
	List<ScheduleCount> countConfirmedByScheduleIds(Collection<Long> scheduleIds);

	/** 알림을 받는 사람(필수와 선택 참여자). 제외된 사람은 일정 알림을 받지 않는다. */
	List<Long> findNotifiedAccountIds(Long scheduleId);

}
