package com.odo.schedule.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.odo.schedule.application.dto.CreateScheduleCommand;
import com.odo.schedule.application.dto.PracticeRoomResult;
import com.odo.schedule.application.dto.ScheduleDetailResult;
import com.odo.schedule.application.dto.ScheduleListResult;
import com.odo.schedule.application.dto.ScheduleListStatus;
import com.odo.schedule.application.dto.ScheduleSummaryResult;
import com.odo.schedule.application.dto.UpdateScheduleCommand;
import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.PracticeRoom;
import com.odo.schedule.domain.entity.Schedule;
import com.odo.schedule.domain.entity.ScheduleParticipant;
import com.odo.schedule.domain.entity.Vote;
import com.odo.schedule.domain.event.ScheduleCreatedEvent;
import com.odo.schedule.domain.event.ScheduleDeletedEvent;
import com.odo.schedule.domain.event.ScheduleUpdatedEvent;
import com.odo.schedule.domain.exception.DuplicateMemberException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomException;
import com.odo.schedule.domain.exception.NotTeamMemberException;
import com.odo.schedule.domain.repository.PracticeRoomRepository;
import com.odo.schedule.domain.repository.ScheduleCount;
import com.odo.schedule.domain.repository.ScheduleParticipantRepository;
import com.odo.schedule.domain.repository.ScheduleRepository;
import com.odo.team.application.TeamAccess;
import com.odo.team.domain.entity.TeamMember;
import com.odo.team.domain.repository.TeamMemberRepository;
import com.odo.team.domain.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일정 만들기, 목록, 상세, 수정, 삭제. 03_Schedule_spec.md의 "일정"과 API 명세.
 * 등록, 수정, 삭제는 팀장만 하고, 같은 트랜잭션 안에서 사실 이벤트를 발행한다. 이벤트에 관련된 사람(제외되지 않은 필수와 선택 참여자)을
 * 담고, 알림 기능이 받아 알림으로 바꾼다(조작한 팀장 본인은 알림에서 빠진다). 11_Notification_spec.md의 "이벤트와 알림".
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

	private final ScheduleRepository scheduleRepository;

	private final ScheduleParticipantRepository participantRepository;

	private final PracticeRoomRepository practiceRoomRepository;

	private final TeamRepository teamRepository;

	private final TeamMemberRepository teamMemberRepository;

	private final TeamAccess teamAccess;

	private final ScheduleAccess scheduleAccess;

	private final ApplicationEventPublisher eventPublisher;

	private final Clock clock;

	/**
	 * 일정을 만들고 팀원 전원의 참여자 행을 넣는다. 필수로 보낸 사람은 필수, 나머지는 투표를 받으면 선택, 받지 않으면 제외다.
	 * 팀원 목록을 읽기 전에 팀 행을 잠근다. 팀원 승인도 같은 행을 잠그므로, 동시에 들어온 새 팀원이 이 일정에서 빠지지 않는다
	 * (03_Schedule_spec.md의 "참여자 행과 새 팀원").
	 * 팀원 아님 TeamNotFoundException, 팀장 아님 NotTeamLeaderException, 고를 수 없는 연습실 InvalidPracticeRoomException,
	 * 필수 참여자 중복 DuplicateMemberException, 팀원 아닌 필수 참여자 NotTeamMemberException, 입력 규칙 위반은 그 항목의 예외.
	 */
	@Transactional
	public ScheduleDetailResult createSchedule(long accountId, long teamId, CreateScheduleCommand command) {
		teamRepository.lockById(teamId);
		teamAccess.requireLeader(teamId, accountId);
		Instant now = clock.instant();
		Long practiceRoomId = requireSelectableRoom(teamId, command.practiceRoomId());
		List<TeamMember> members = teamMemberRepository.findAllByTeamId(teamId);
		Set<Long> required = requireRequiredMembers(command.requiredAccountIds(), members);
		Schedule schedule = scheduleRepository.save(Schedule.createPractice(teamId, command.title(), command.startAt(),
				command.durationMinutes(), practiceRoomId, command.memo(), command.votingEnabled(),
				command.voteDeadline(), command.capacity(), now));
		List<ScheduleParticipant> participants = members.stream()
			.map(member -> ScheduleParticipant.createParticipant(schedule.getId(), member.getAccountId(),
					required.contains(member.getAccountId()) ? ParticipantStatus.REQUIRED
							: schedule.defaultParticipantStatus(),
					now))
			.toList();
		participantRepository.saveAll(participants);
		List<Long> notified = participants.stream()
			.filter(participant -> participant.getStatus() != ParticipantStatus.EXCLUDED)
			.map(ScheduleParticipant::getAccountId)
			.toList();
		eventPublisher.publishEvent(new ScheduleCreatedEvent(teamId, schedule.getId(), accountId, notified));
		return detailOf(schedule, accountId, now);
	}

	/** 예정이나 완료 한 페이지. 팀원 아님 TeamNotFoundException. */
	@Transactional(readOnly = true)
	public ScheduleListResult findSchedules(long accountId, long teamId, ScheduleListStatus status, int page,
			int size) {
		teamAccess.requireMember(teamId, accountId);
		Instant now = clock.instant();
		PageRequest pageRequest = PageRequest.of(page, size);
		Page<Schedule> found = status == ScheduleListStatus.UPCOMING
				? scheduleRepository.findByTeamIdAndEndAtAfterOrderByStartAtAscIdAsc(teamId, now, pageRequest)
				: scheduleRepository.findByTeamIdAndEndAtLessThanEqualOrderByEndAtDescIdDesc(teamId, now, pageRequest);
		List<Schedule> schedules = found.getContent();
		List<Long> scheduleIds = schedules.stream().map(Schedule::getId).toList();
		Map<Long, Long> confirmed = scheduleIds.isEmpty() ? Map.of()
				: participantRepository.countConfirmedByScheduleIds(scheduleIds)
					.stream()
					.collect(Collectors.toMap(ScheduleCount::getScheduleId, ScheduleCount::getCount));
		Map<Long, ScheduleParticipant> mine = scheduleIds.isEmpty() ? Map.of()
				: participantRepository.findAllByAccountIdAndScheduleIdIn(accountId, scheduleIds)
					.stream()
					.collect(Collectors.toMap(ScheduleParticipant::getScheduleId, Function.identity()));
		Map<Long, String> roomNames = roomNamesOf(schedules);
		List<ScheduleSummaryResult> items = schedules.stream().map(schedule -> {
			ScheduleParticipant me = mine.get(schedule.getId());
			return new ScheduleSummaryResult(schedule.getId(), schedule.getTitle(), schedule.getStartAt(),
					schedule.getEndAt(), roomNameOf(roomNames, schedule), schedule.isVotingEnabled(),
					schedule.getVoteDeadline(), schedule.getCapacity(), confirmed.getOrDefault(schedule.getId(), 0L),
					me == null ? null : me.getStatus(), me == null ? null : me.getVote());
		}).toList();
		return new ScheduleListResult(items, page, size, found.getTotalElements());
	}

	/** 없음, 팀원 아님은 ScheduleNotFoundException. */
	@Transactional(readOnly = true)
	public ScheduleDetailResult findSchedule(long accountId, long scheduleId) {
		Schedule schedule = scheduleAccess.requireVisibleSchedule(scheduleId, accountId);
		return detailOf(schedule, accountId, clock.instant());
	}

	/**
	 * 보낸 값으로 모두 바꾼다. 일정 행을 잠그고 고치므로 투표 처리와 겹쳐도 정원이 참여 인원보다 작아지지 않는다.
	 * 장소를 바꾸지 않으면 삭제한 연습실이어도 그대로 둔다. 나머지 규칙은 Schedule.update.
	 * 없음, 팀원 아님 ScheduleNotFoundException, 팀장 아님 NotTeamLeaderException, 종료됨 ScheduleEndedException.
	 */
	@Transactional
	public ScheduleDetailResult updateSchedule(long accountId, long scheduleId, UpdateScheduleCommand command) {
		Schedule schedule = scheduleAccess.lockScheduleAsLeader(scheduleId, accountId);
		Instant now = clock.instant();
		schedule.requireNotEnded(now);
		Long practiceRoomId = Objects.equals(command.practiceRoomId(), schedule.getPracticeRoomId())
				? schedule.getPracticeRoomId() : requireSelectableRoom(schedule.getTeamId(), command.practiceRoomId());
		long confirmedCount = participantRepository.countByScheduleIdAndStatusAndVote(scheduleId,
				ParticipantStatus.OPTIONAL, Vote.YES);
		schedule.update(command.title(), command.startAt(), command.durationMinutes(), practiceRoomId, command.memo(),
				command.voteDeadline(), command.capacity(), confirmedCount, now);
		eventPublisher.publishEvent(new ScheduleUpdatedEvent(schedule.getTeamId(), scheduleId, accountId,
				participantRepository.findNotifiedAccountIds(scheduleId)));
		return detailOf(schedule, accountId, now);
	}

	/**
	 * 일정을 지운다. 참여자 행은 DB에서 함께 지워진다(on delete cascade). 종료나 마감과 관계없이 지울 수 있다.
	 * 삭제 이벤트에 제목을 담아 알림에 복사해 둔다. 지운 뒤에는 일정을 다시 읽을 수 없기 때문이다.
	 * 없음, 이미 지움, 팀원 아님 ScheduleNotFoundException, 팀장 아님 NotTeamLeaderException.
	 */
	@Transactional
	public void deleteSchedule(long accountId, long scheduleId) {
		Schedule schedule = scheduleAccess.lockScheduleAsLeader(scheduleId, accountId);
		List<Long> notified = participantRepository.findNotifiedAccountIds(scheduleId);
		eventPublisher.publishEvent(
				new ScheduleDeletedEvent(schedule.getTeamId(), scheduleId, schedule.getTitle(), accountId, notified));
		scheduleRepository.delete(schedule);
	}

	/** 장소가 없으면 null. 그 팀의 삭제하지 않은 연습실이어야 한다. 아니면 InvalidPracticeRoomException(본문의 ID가 틀림, 400). */
	private Long requireSelectableRoom(long teamId, Long practiceRoomId) {
		if (practiceRoomId == null) {
			return null;
		}
		PracticeRoom room = practiceRoomRepository.findById(practiceRoomId)
			.filter(found -> found.getTeamId() == teamId && !found.isDeleted())
			.orElseThrow(InvalidPracticeRoomException::new);
		return room.getId();
	}

	/** 필수로 정할 사람. 같은 사람이 두 번이면 DuplicateMemberException, 그 팀의 팀원이 아니면 NotTeamMemberException. */
	private static Set<Long> requireRequiredMembers(List<Long> requiredAccountIds, List<TeamMember> members) {
		Set<Long> required = new HashSet<>();
		for (Long accountId : requiredAccountIds) {
			if (!required.add(accountId)) {
				throw new DuplicateMemberException();
			}
		}
		Set<Long> memberIds = members.stream().map(TeamMember::getAccountId).collect(Collectors.toSet());
		if (!memberIds.containsAll(required)) {
			throw new NotTeamMemberException();
		}
		return required;
	}

	private ScheduleDetailResult detailOf(Schedule schedule, long accountId, Instant now) {
		PracticeRoomResult room = schedule.getPracticeRoomId() == null ? null
				: practiceRoomRepository.findById(schedule.getPracticeRoomId()).map(PracticeRoomResult::of).orElse(null);
		long confirmedCount = participantRepository.countByScheduleIdAndStatusAndVote(schedule.getId(),
				ParticipantStatus.OPTIONAL, Vote.YES);
		long requiredCount = participantRepository.countByScheduleIdAndStatus(schedule.getId(),
				ParticipantStatus.REQUIRED);
		ScheduleParticipant me = participantRepository.findByScheduleIdAndAccountId(schedule.getId(), accountId)
			.orElse(null);
		return new ScheduleDetailResult(schedule.getId(), schedule.getTeamId(), schedule.getType(), schedule.getTitle(),
				schedule.getStartAt(), schedule.getDurationMinutes(), schedule.getEndAt(),
				schedule.isEnded(now) ? ScheduleListStatus.DONE : ScheduleListStatus.UPCOMING, room, schedule.getMemo(),
				schedule.isVotingEnabled(), schedule.getVoteDeadline(), schedule.isVotingClosed(now),
				schedule.getCapacity(), confirmedCount, requiredCount, me == null ? null : me.getStatus(),
				me == null ? null : me.getVote(), schedule.getCreatedAt(), schedule.getUpdatedAt());
	}

	private Map<Long, String> roomNamesOf(List<Schedule> schedules) {
		List<Long> roomIds = schedules.stream().map(Schedule::getPracticeRoomId).filter(Objects::nonNull).distinct().toList();
		if (roomIds.isEmpty()) {
			return Map.of();
		}
		return practiceRoomRepository.findAllById(roomIds)
			.stream()
			.collect(Collectors.toMap(PracticeRoom::getId, PracticeRoom::getName));
	}

	/** 장소가 없으면 null. Map.of()의 get(null)은 NPE를 던지므로 먼저 본다. */
	private static String roomNameOf(Map<Long, String> roomNames, Schedule schedule) {
		return schedule.getPracticeRoomId() == null ? null : roomNames.get(schedule.getPracticeRoomId());
	}

}
