package com.odo.schedule.application;

import java.time.Clock;
import java.util.List;

import com.odo.schedule.application.dto.PracticeRoomCommand;
import com.odo.schedule.application.dto.PracticeRoomResult;
import com.odo.schedule.domain.entity.PracticeRoom;
import com.odo.schedule.domain.exception.PracticeRoomNotFoundException;
import com.odo.schedule.domain.repository.PracticeRoomRepository;
import com.odo.team.application.TeamAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연습실 만들기, 목록, 상세, 수정, 삭제. 03_Schedule_spec.md의 "장소와 연습실 목록"과 API 명세.
 * 등록, 수정, 삭제는 팀장만, 보기는 팀원 누구나 한다.
 */
@Service
@RequiredArgsConstructor
public class PracticeRoomService {

	private final PracticeRoomRepository practiceRoomRepository;

	private final TeamAccess teamAccess;

	private final ScheduleAccess scheduleAccess;

	private final Clock clock;

	/** 팀원 아님 TeamNotFoundException, 팀장 아님 NotTeamLeaderException, 항목 규칙 위반은 그 항목의 예외. */
	@Transactional
	public PracticeRoomResult createRoom(long accountId, long teamId, PracticeRoomCommand command) {
		teamAccess.requireLeader(teamId, accountId);
		PracticeRoom room = PracticeRoom.createRoom(teamId, command.name(), command.address(), command.password(),
				command.rentalFee(), command.memo(), clock.instant());
		return PracticeRoomResult.of(practiceRoomRepository.save(room));
	}

	/** 삭제하지 않은 연습실, 이름순. 팀원 아님 TeamNotFoundException. */
	@Transactional(readOnly = true)
	public List<PracticeRoomResult> findRooms(long accountId, long teamId) {
		teamAccess.requireMember(teamId, accountId);
		return practiceRoomRepository.findAllByTeamIdAndDeletedAtIsNullOrderByNameAscIdAsc(teamId)
			.stream()
			.map(PracticeRoomResult::of)
			.toList();
	}

	/** 없음, 삭제됨, 팀원 아님은 PracticeRoomNotFoundException. */
	@Transactional(readOnly = true)
	public PracticeRoomResult findRoom(long accountId, long roomId) {
		return PracticeRoomResult.of(scheduleAccess.requireVisiblePracticeRoom(roomId, accountId));
	}

	/**
	 * 보낸 값으로 모두 바꾼다. 행을 잠그고 고치므로 동시에 들어온 삭제가 이 수정에 덮이지 않는다.
	 * 없음, 삭제됨, 팀원 아님 PracticeRoomNotFoundException, 팀장 아님 NotTeamLeaderException.
	 */
	@Transactional
	public PracticeRoomResult updateRoom(long accountId, long roomId, PracticeRoomCommand command) {
		PracticeRoom room = scheduleAccess.lockPracticeRoomAsLeader(roomId, accountId);
		room.update(command.name(), command.address(), command.password(), command.rentalFee(), command.memo(),
				clock.instant());
		return PracticeRoomResult.of(room);
	}

	/**
	 * 삭제 표시를 한다. 그 연습실을 쓴 일정에서는 정보가 계속 보인다.
	 * 조건부 UPDATE라 동시에 두 번 지우면 하나만 성공하고 나머지는 PracticeRoomNotFoundException이다.
	 */
	@Transactional
	public void deleteRoom(long accountId, long roomId) {
		scheduleAccess.requirePracticeRoomAsLeader(roomId, accountId);
		if (practiceRoomRepository.markDeleted(roomId, clock.instant()) == 0) {
			throw new PracticeRoomNotFoundException();
		}
	}

}
