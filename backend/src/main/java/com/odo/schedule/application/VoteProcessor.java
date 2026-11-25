package com.odo.schedule.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.Schedule;
import com.odo.schedule.domain.entity.ScheduleParticipant;
import com.odo.schedule.domain.entity.Vote;
import com.odo.schedule.domain.entity.VoteInvalidReason;
import com.odo.schedule.domain.entity.VoteRequest;
import com.odo.schedule.domain.repository.ScheduleParticipantRepository;
import com.odo.schedule.domain.repository.ScheduleRepository;
import com.odo.schedule.domain.repository.VoteRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 투표 작업자. 도착 시각 + W가 지난 투표 요청을 일정마다 도착 시각 순서로 처리한다.
 * 03_Schedule_spec.md의 "처리 (작업자)", 판정 표, "두 번 처리 막기", "역전 기록".
 * <p>
 * 일정마다 트랜잭션이 따로다. 일정 행을 FOR UPDATE SKIP LOCKED로 잠가서 한 일정은 한 작업자만 처리하고,
 * 다른 작업자는 그 일정을 건너뛰어 다른 일정을 처리한다. 요청의 결과와 참여자 행 변경은 한 트랜잭션이라
 * 처리 중에 서버가 죽으면 둘 다 롤백되고 다음 폴링에서 다시 처리된다.
 * <p>
 * 꺼낸 요청은 꺼낼 때의 최신 상태(정원, 마감, 참여자 상태)로 판단한다. 팀장의 조작도 같은 일정 행 잠금을 쓴다.
 */
@Service
public class VoteProcessor {

	private static final Logger log = LoggerFactory.getLogger(VoteProcessor.class);

	/** 한 번의 폴링에서 처리할 일정 수의 상한. 남은 일정은 다음 폴링이나 다른 서버가 처리한다. */
	static final int MAX_SCHEDULES_PER_POLL = 100;

	private final VoteRequestRepository voteRequestRepository;

	private final ScheduleRepository scheduleRepository;

	private final ScheduleParticipantRepository participantRepository;

	private final VoteProperties properties;

	private final Clock clock;

	private final TransactionTemplate transactionTemplate;

	public VoteProcessor(VoteRequestRepository voteRequestRepository, ScheduleRepository scheduleRepository,
			ScheduleParticipantRepository participantRepository, VoteProperties properties, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.voteRequestRepository = voteRequestRepository;
		this.scheduleRepository = scheduleRepository;
		this.participantRepository = participantRepository;
		this.properties = properties;
		this.clock = clock;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * 처리할 때가 된 요청을 처리하고, 처리한 요청 수를 돌려준다. "지금"은 이 서버의 시계다.
	 * 한 일정에서 오류가 나도 그 일정만 롤백하고 다른 일정은 계속 처리한다. 롤백된 요청은 다음 폴링에서 다시 처리된다.
	 */
	public int processDue() {
		Instant now = clock.instant();
		Instant cutoff = now.minus(properties.waitTime());
		int processed = 0;
		for (Long scheduleId : voteRequestRepository.findDueScheduleIds(cutoff, MAX_SCHEDULES_PER_POLL)) {
			try {
				Integer count = transactionTemplate.execute(status -> processSchedule(scheduleId, cutoff, now));
				processed += count == null ? 0 : count;
			}
			catch (RuntimeException ex) {
				log.error("투표 요청을 처리하지 못했다. 다음 폴링에서 다시 처리한다: scheduleId={}", scheduleId, ex);
			}
		}
		return processed;
	}

	private int processSchedule(long scheduleId, Instant cutoff, Instant now) {
		Optional<Schedule> locked = scheduleRepository.lockByIdSkipLocked(scheduleId);
		if (locked.isEmpty()) {
			// 잠그지 못했다. 행이 있으면 다른 트랜잭션이 처리 중이므로 건너뛰고, 없으면 삭제된 일정이다.
			return scheduleRepository.existsById(scheduleId) ? 0 : invalidateDeleted(scheduleId, cutoff, now);
		}
		Schedule schedule = locked.get();
		List<VoteRequest> due = voteRequestRepository.findDue(scheduleId, cutoff);
		if (due.isEmpty()) {
			return 0;
		}
		Map<Long, ScheduleParticipant> participants = participantRepository
			.findAllByScheduleIdOrderByAccountIdAsc(scheduleId)
			.stream()
			.collect(Collectors.toMap(ScheduleParticipant::getAccountId, Function.identity()));
		// 참여 인원은 처음에 한 번 센다. 일정 행을 잠근 동안에는 다른 곳에서 바뀌지 않으므로 메모리에서 더하고 뺀다.
		long confirmed = participantRepository.countByScheduleIdAndStatusAndVote(scheduleId, ParticipantStatus.OPTIONAL,
				Vote.YES);
		for (VoteRequest request : due) {
			boolean reordered = schedule.isReordered(request.getArrivedAt());
			if (reordered) {
				log.warn("투표 요청 역전: requestId={}, scheduleId={}, arrivedAt={}, 앞서 처리한 가장 늦은 도착={}, receivedBy={}",
						request.getId(), scheduleId, request.getArrivedAt(), schedule.getLastConfirmedArrivedAt(),
						request.getReceivedBy());
			}
			confirmed += apply(schedule, participants.get(request.getAccountId()), request, confirmed, reordered, now);
			schedule.recordProcessedArrival(request.getArrivedAt());
		}
		return due.size();
	}

	/**
	 * 판정 표의 순서대로 확인하고 요청 결과와 참여자 행을 함께 고친다. 참여 인원의 변화(+1, 0, −1)를 돌려준다.
	 * participant가 null이면 그 일정의 참여자가 아니다(팀원 아님).
	 */
	private static long apply(Schedule schedule, ScheduleParticipant participant, VoteRequest request, long confirmed,
			boolean reordered, Instant now) {
		VoteInvalidReason invalid = invalidReason(schedule, participant, request);
		if (invalid != null) {
			request.invalidate(invalid, reordered, now);
			return 0;
		}
		Vote before = participant.getVote();
		if (request.getChoice() == Vote.NO) {
			participant.castVote(Vote.NO, now);
			request.confirm(reordered, now);
			return before == Vote.YES ? -1 : 0;
		}
		if (before == Vote.YES) {
			request.confirm(reordered, now);
			return 0;
		}
		if (schedule.hasSeat(confirmed)) {
			participant.castVote(Vote.YES, now);
			request.confirm(reordered, now);
			return 1;
		}
		request.full(reordered, now);
		return 0;
	}

	/** 판정 표의 1~4. 무효가 아니면 null. */
	private static VoteInvalidReason invalidReason(Schedule schedule, ScheduleParticipant participant,
			VoteRequest request) {
		if (participant == null) {
			return VoteInvalidReason.SCHEDULE_NOT_FOUND;
		}
		if (!schedule.isVotingEnabled()) {
			return VoteInvalidReason.NOT_VOTING_SCHEDULE;
		}
		if (schedule.isVotingClosed(request.getArrivedAt())) {
			return VoteInvalidReason.VOTING_CLOSED;
		}
		if (!participant.isOptional()) {
			return VoteInvalidReason.NOT_OPTIONAL_PARTICIPANT;
		}
		return null;
	}

	/**
	 * 삭제된 일정의 대기 요청을 모두 무효(SCHEDULE_NOT_FOUND)로 끝낸다. 일정 행이 없어 일정 행 잠금으로 차례를 정할 수 없으므로
	 * 요청 행을 SKIP LOCKED로 잠가, 다른 작업자가 같은 요청을 함께 처리하지 않게 한다.
	 */
	private int invalidateDeleted(long scheduleId, Instant cutoff, Instant now) {
		List<VoteRequest> due = voteRequestRepository.lockDueSkipLocked(scheduleId, cutoff);
		due.forEach(request -> request.invalidate(VoteInvalidReason.SCHEDULE_NOT_FOUND, false, now));
		return due.size();
	}

}
