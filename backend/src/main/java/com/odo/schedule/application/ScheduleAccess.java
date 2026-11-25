package com.odo.schedule.application;

import java.util.function.Supplier;

import com.odo.schedule.domain.entity.PracticeRoom;
import com.odo.schedule.domain.entity.Schedule;
import com.odo.schedule.domain.exception.PracticeRoomNotFoundException;
import com.odo.schedule.domain.exception.ScheduleNotFoundException;
import com.odo.schedule.domain.repository.PracticeRoomRepository;
import com.odo.schedule.domain.repository.ScheduleRepository;
import com.odo.team.domain.entity.TeamMember;
import com.odo.team.domain.exception.NotTeamLeaderException;
import com.odo.team.domain.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 일정과 연습실 URL(/api/schedules/{id}, /api/practice-rooms/{id})의 권한 확인. 03_Schedule_spec.md의 "권한 확인".
 * 자원이 없거나 그 팀의 팀원이 아니면 404(SCHEDULE_NOT_FOUND, PRACTICE_ROOM_NOT_FOUND)다. 403을 주면 그 ID의 자원이 있다는 것이 드러난다.
 * 팀원이지만 팀장이 아니면 NotTeamLeaderException(403). 요청마다 DB에서 팀원을 확인한다.
 * 호출하는 쪽의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class ScheduleAccess {

	private final ScheduleRepository scheduleRepository;

	private final PracticeRoomRepository practiceRoomRepository;

	private final TeamMemberRepository teamMemberRepository;

	/** 팀원이 볼 수 있는 일정. 제외된 사람도 팀원이므로 볼 수 있다. */
	public Schedule requireVisibleSchedule(long scheduleId, long accountId) {
		Schedule schedule = scheduleRepository.findById(scheduleId).orElseThrow(ScheduleNotFoundException::new);
		requireMember(schedule.getTeamId(), accountId, ScheduleNotFoundException::new);
		return schedule;
	}

	/** 팀장이 고칠 일정. 일정 행을 FOR UPDATE로 잠근 뒤 확인한다. 이 트랜잭션이 끝날 때까지 잠금을 잡는다. */
	public Schedule lockScheduleAsLeader(long scheduleId, long accountId) {
		Schedule schedule = scheduleRepository.lockById(scheduleId).orElseThrow(ScheduleNotFoundException::new);
		requireLeader(schedule.getTeamId(), accountId, ScheduleNotFoundException::new);
		return schedule;
	}

	/** 팀원이 볼 수 있는 연습실. 삭제한 연습실은 없는 것으로 본다. */
	public PracticeRoom requireVisiblePracticeRoom(long roomId, long accountId) {
		PracticeRoom room = practiceRoomRepository.findById(roomId)
			.filter(found -> !found.isDeleted())
			.orElseThrow(PracticeRoomNotFoundException::new);
		requireMember(room.getTeamId(), accountId, PracticeRoomNotFoundException::new);
		return room;
	}

	/** 팀장이 지울 연습실. 삭제 자체는 조건부 UPDATE로 하므로 잠그지 않는다. */
	public PracticeRoom requirePracticeRoomAsLeader(long roomId, long accountId) {
		PracticeRoom room = practiceRoomRepository.findById(roomId)
			.filter(found -> !found.isDeleted())
			.orElseThrow(PracticeRoomNotFoundException::new);
		requireLeader(room.getTeamId(), accountId, PracticeRoomNotFoundException::new);
		return room;
	}

	/** 팀장이 고칠 연습실. 행을 FOR UPDATE로 잠근 뒤 확인한다. 그사이 삭제됐으면 없는 것으로 본다. */
	public PracticeRoom lockPracticeRoomAsLeader(long roomId, long accountId) {
		PracticeRoom room = practiceRoomRepository.lockById(roomId)
			.filter(found -> !found.isDeleted())
			.orElseThrow(PracticeRoomNotFoundException::new);
		requireLeader(room.getTeamId(), accountId, PracticeRoomNotFoundException::new);
		return room;
	}

	private TeamMember requireMember(long teamId, long accountId, Supplier<RuntimeException> notFound) {
		return teamMemberRepository.findByTeamIdAndAccountId(teamId, accountId).orElseThrow(notFound);
	}

	private void requireLeader(long teamId, long accountId, Supplier<RuntimeException> notFound) {
		if (!requireMember(teamId, accountId, notFound).isLeader()) {
			throw new NotTeamLeaderException();
		}
	}

}
