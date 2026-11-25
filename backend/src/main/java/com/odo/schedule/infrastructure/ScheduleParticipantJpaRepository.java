package com.odo.schedule.infrastructure;

import java.util.Collection;
import java.util.List;

import com.odo.schedule.domain.entity.ScheduleParticipant;
import com.odo.schedule.domain.entity.ScheduleParticipantId;
import com.odo.schedule.domain.repository.ScheduleCount;
import com.odo.schedule.domain.repository.ScheduleParticipantRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * ScheduleParticipantRepository의 JPA 구현.
 */
interface ScheduleParticipantJpaRepository
		extends JpaRepository<ScheduleParticipant, ScheduleParticipantId>, ScheduleParticipantRepository {

	@Override
	@Query("""
			select p.scheduleId as scheduleId, count(p) as count
			from ScheduleParticipant p
			where p.scheduleId in :scheduleIds
				and p.status = com.odo.schedule.domain.entity.ParticipantStatus.OPTIONAL
				and p.vote = com.odo.schedule.domain.entity.Vote.YES
			group by p.scheduleId
			""")
	List<ScheduleCount> countConfirmedByScheduleIds(@Param("scheduleIds") Collection<Long> scheduleIds);

	@Override
	@Query("""
			select p.accountId from ScheduleParticipant p
			where p.scheduleId = :scheduleId
				and p.status <> com.odo.schedule.domain.entity.ParticipantStatus.EXCLUDED
			order by p.accountId
			""")
	List<Long> findNotifiedAccountIds(@Param("scheduleId") Long scheduleId);

}
