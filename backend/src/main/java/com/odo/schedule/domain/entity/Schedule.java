package com.odo.schedule.domain.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.odo.common.text.UserTextRule;
import com.odo.schedule.domain.exception.CapacityBelowConfirmedException;
import com.odo.schedule.domain.exception.InvalidCapacityException;
import com.odo.schedule.domain.exception.InvalidDurationException;
import com.odo.schedule.domain.exception.InvalidScheduleMemoException;
import com.odo.schedule.domain.exception.InvalidScheduleTitleException;
import com.odo.schedule.domain.exception.InvalidStartAtException;
import com.odo.schedule.domain.exception.InvalidStatusException;
import com.odo.schedule.domain.exception.InvalidVoteDeadlineException;
import com.odo.schedule.domain.exception.NotVotingScheduleException;
import com.odo.schedule.domain.exception.ScheduleEndedException;
import com.odo.schedule.domain.exception.VotingClosedException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 팀의 일정. 1차는 연습 일정만 만든다. 03_Schedule_spec.md의 "일정", "연습 일정의 참여자와 참여 투표".
 * 종료 시간(endAt)은 시작 시간에 연습 시간을 더해 저장한다. 완료는 endAt이 지난 것이다.
 * 투표 여부는 만들 때만 정한다. 입력 규칙은 검증한 뒤 저장할 값을 돌려주는 public static 메서드로 둔다.
 * 수정과 삭제는 일정 행을 FOR UPDATE로 잠근 뒤 한다(ScheduleRepository.lockById).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "schedule")
public class Schedule {

	private static final int TITLE_MAX_LENGTH = 50;

	private static final int MEMO_MAX_LENGTH = 300;

	private static final int DURATION_UNIT_MINUTES = 30;

	private static final int DURATION_MIN_MINUTES = 30;

	private static final int DURATION_MAX_MINUTES = 1440;

	/** 시작 시간과 마감 기한의 단위. 한국 시간은 정각 단위의 시차라 UTC로 확인해도 같다. */
	private static final long TIME_UNIT_SECONDS = Duration.ofMinutes(10).toSeconds();

	private static final Duration DEFAULT_DEADLINE_AFTER = Duration.ofHours(24);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ScheduleType type;

	@Column(nullable = false, length = TITLE_MAX_LENGTH)
	private String title;

	@Column(name = "start_at", nullable = false)
	private Instant startAt;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(name = "end_at", nullable = false)
	private Instant endAt;

	@Column(name = "practice_room_id")
	private Long practiceRoomId;

	@Column(length = MEMO_MAX_LENGTH)
	private String memo;

	@Column(name = "voting_enabled", nullable = false)
	private boolean votingEnabled;

	@Column(name = "vote_deadline")
	private Instant voteDeadline;

	private Integer capacity;

	/** 마지막으로 처리한 투표 요청의 도착 시각. 투표 처리(작업자)가 역전을 기록할 때 쓴다. */
	@Column(name = "last_confirmed_arrived_at")
	private Instant lastConfirmedArrivedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * 연습 일정을 만든다. 연습실은 서비스가 고를 수 있는 것인지 확인한 뒤 넘긴다.
	 * 투표를 받는데 마감 기한이 없으면 기본값을 쓴다. 투표를 받지 않는데 마감 기한이나 정원이 있으면 NotVotingScheduleException.
	 */
	public static Schedule createPractice(long teamId, String title, Instant startAt, int durationMinutes,
			Long practiceRoomId, String memo, boolean votingEnabled, Instant voteDeadline, Integer capacity, Instant now) {
		Schedule schedule = new Schedule();
		schedule.teamId = teamId;
		schedule.type = ScheduleType.PRACTICE;
		schedule.title = requireValidTitle(title);
		schedule.memo = requireValidMemo(memo);
		schedule.startAt = requireValidStartAt(startAt, now);
		schedule.durationMinutes = requireValidDuration(durationMinutes);
		schedule.endAt = endOf(schedule.startAt, schedule.durationMinutes);
		schedule.practiceRoomId = practiceRoomId;
		schedule.votingEnabled = votingEnabled;
		if (votingEnabled) {
			schedule.voteDeadline = voteDeadline == null ? defaultVoteDeadline(now, schedule.startAt)
					: requireValidVoteDeadline(voteDeadline, now, schedule.startAt);
			schedule.capacity = requireValidCapacity(capacity);
		}
		else if (voteDeadline != null || capacity != null) {
			throw new NotVotingScheduleException();
		}
		schedule.createdAt = now;
		schedule.updatedAt = now;
		return schedule;
	}

	/**
	 * 보낸 값으로 모두 바꾼다. 투표 여부는 바꾸지 않는다. 03_Schedule_spec.md의 "일정 수정".
	 * 저장된 값과 같은 시작 시간과 마감 기한은 다시 검사하지 않는다. 연습실은 서비스가 확인한다.
	 * 종료됨 ScheduleEndedException, 고친 뒤 종료가 현재 이전 InvalidDurationException,
	 * 마감 뒤 마감 기한이나 정원 변경 VotingClosedException, 지금 참여 인원보다 작은 정원 CapacityBelowConfirmedException.
	 * 투표 일정에서 마감 기한이 없으면 만들 때처럼 기본값을 쓴다.
	 */
	public void update(String title, Instant startAt, int durationMinutes, Long practiceRoomId, String memo,
			Instant voteDeadline, Integer capacity, long confirmedCount, Instant now) {
		requireNotEnded(now);
		String newTitle = requireValidTitle(title);
		String newMemo = requireValidMemo(memo);
		Instant newStartAt = startAt.equals(this.startAt) ? startAt : requireValidStartAt(startAt, now);
		int newDuration = requireValidDuration(durationMinutes);
		Instant newEndAt = endOf(newStartAt, newDuration);
		if (!newEndAt.isAfter(now)) {
			throw new InvalidDurationException();
		}
		Instant newDeadline = null;
		Integer newCapacity = null;
		if (votingEnabled) {
			newDeadline = voteDeadline == null ? defaultVoteDeadline(now, newStartAt) : voteDeadline;
			newCapacity = capacity;
			boolean deadlineChanged = !newDeadline.equals(this.voteDeadline);
			boolean capacityChanged = !Objects.equals(newCapacity, this.capacity);
			if (isVotingClosed(now) && (deadlineChanged || capacityChanged)) {
				throw new VotingClosedException();
			}
			if (deadlineChanged) {
				requireValidVoteDeadline(newDeadline, now, newStartAt);
			}
			else if (newDeadline.isAfter(newStartAt)) {
				// 시작 시간을 마감 기한보다 앞으로 옮기면 마감 기한도 함께 고쳐야 한다
				throw new InvalidVoteDeadlineException();
			}
			if (capacityChanged) {
				requireValidCapacity(newCapacity);
				if (newCapacity != null && newCapacity < confirmedCount) {
					throw new CapacityBelowConfirmedException();
				}
			}
		}
		else if (voteDeadline != null || capacity != null) {
			throw new NotVotingScheduleException();
		}
		this.title = newTitle;
		this.memo = newMemo;
		this.startAt = newStartAt;
		this.durationMinutes = newDuration;
		this.endAt = newEndAt;
		this.practiceRoomId = practiceRoomId;
		this.voteDeadline = newDeadline;
		this.capacity = newCapacity;
		this.updatedAt = now;
	}

	/** 종료 시간이 지났으면 완료다. 진행 중인 일정은 완료가 아니다. */
	public boolean isEnded(Instant now) {
		return !endAt.isAfter(now);
	}

	/** 종료된 일정이면 ScheduleEndedException. 수정과 참여자 상태 변경은 종료 전까지만 한다. */
	public void requireNotEnded(Instant now) {
		if (isEnded(now)) {
			throw new ScheduleEndedException();
		}
	}

	/** 투표 일정이고 마감 기한이 되었는지. 마감과 같은 시각부터 닫힌다. */
	public boolean isVotingClosed(Instant now) {
		return votingEnabled && !now.isBefore(voteDeadline);
	}

	/** 정원이 없거나, 참여한 선택 참여자 수가 정원보다 적으면 자리가 있다. */
	public boolean hasSeat(long confirmedCount) {
		return capacity == null || confirmedCount < capacity;
	}

	/**
	 * 이 도착 시각의 요청을 지금 처리하면 역전인지. 이미 처리한 요청 중 가장 늦은 도착 시각보다 이르면 역전이다.
	 * 03_Schedule_spec.md의 "역전 기록".
	 */
	public boolean isReordered(Instant arrivedAt) {
		return lastConfirmedArrivedAt != null && arrivedAt.isBefore(lastConfirmedArrivedAt);
	}

	/**
	 * 처리한 투표 요청의 도착 시각을 남긴다. 늦게 처리한 이른 요청이 기준을 앞으로 당기지 않도록 가장 늦은 값만 둔다.
	 * 당겨지면 그다음에 늦게 온 요청이 역전으로 잡히지 않는다. 결과(확정, 정원 마감, 무효)와 관계없이 남긴다.
	 */
	public void recordProcessedArrival(Instant arrivedAt) {
		if (lastConfirmedArrivedAt == null || arrivedAt.isAfter(lastConfirmedArrivedAt)) {
			this.lastConfirmedArrivedAt = arrivedAt;
		}
	}

	/** 일정을 만들거나 팀원이 들어올 때의 상태. 투표를 받으면 선택, 받지 않으면 제외다. */
	public ParticipantStatus defaultParticipantStatus() {
		return votingEnabled ? ParticipantStatus.OPTIONAL : ParticipantStatus.EXCLUDED;
	}

	/** 투표 일정은 필수와 선택, 투표 없는 일정은 필수와 제외만 쓴다. 아니면 InvalidStatusException. */
	public void requireAllowedStatus(ParticipantStatus status) {
		ParticipantStatus notAllowed = votingEnabled ? ParticipantStatus.EXCLUDED : ParticipantStatus.OPTIONAL;
		if (status == notAllowed) {
			throw new InvalidStatusException();
		}
	}

	/** 앞뒤 공백을 뺀 1~50자 한 줄. 아니면 InvalidScheduleTitleException. */
	public static String requireValidTitle(String title) {
		String normalized = UserTextRule.normalize(title);
		if (normalized == null || normalized.isEmpty() || codePoints(normalized) > TITLE_MAX_LENGTH
				|| UserTextRule.containsForbiddenCharForSingleLine(normalized)) {
			throw new InvalidScheduleTitleException();
		}
		return normalized;
	}

	/** 비어 있으면 null. 300자 이하이고 줄바꿈을 쓸 수 있다. 아니면 InvalidScheduleMemoException. */
	public static String requireValidMemo(String memo) {
		String normalized = UserTextRule.normalize(memo);
		if (normalized == null || normalized.isEmpty()) {
			return null;
		}
		if (codePoints(normalized) > MEMO_MAX_LENGTH || UserTextRule.containsForbiddenCharForMultiLine(normalized)) {
			throw new InvalidScheduleMemoException();
		}
		return normalized;
	}

	/** 현재 이후의 10분 단위. 아니면 InvalidStartAtException. */
	public static Instant requireValidStartAt(Instant startAt, Instant now) {
		if (!isTimeUnit(startAt) || !startAt.isAfter(now)) {
			throw new InvalidStartAtException();
		}
		return startAt;
	}

	/** 30분 단위로 30분 이상 1,440분 이하. 아니면 InvalidDurationException. */
	public static int requireValidDuration(int minutes) {
		if (minutes < DURATION_MIN_MINUTES || minutes > DURATION_MAX_MINUTES || minutes % DURATION_UNIT_MINUTES != 0) {
			throw new InvalidDurationException();
		}
		return minutes;
	}

	/**
	 * 마감 기한의 기본값. 지금부터 24시간 뒤를 10분 단위로 올린 시각이고, 연습 시작이 그보다 이르면 연습 시작 시간이다.
	 * 예: 15:32에 만들면 다음 날 15:40.
	 */
	public static Instant defaultVoteDeadline(Instant now, Instant startAt) {
		Instant dayLater = now.plus(DEFAULT_DEADLINE_AFTER);
		long seconds = dayLater.getEpochSecond();
		long floored = Math.floorDiv(seconds, TIME_UNIT_SECONDS) * TIME_UNIT_SECONDS;
		Instant roundedUp = floored == seconds && dayLater.getNano() == 0 ? dayLater
				: Instant.ofEpochSecond(floored + TIME_UNIT_SECONDS);
		return startAt.isBefore(roundedUp) ? startAt : roundedUp;
	}

	/** 10분 단위이고 현재 이후이며 시작 시간과 같거나 그 전. 아니면 InvalidVoteDeadlineException. */
	public static Instant requireValidVoteDeadline(Instant deadline, Instant now, Instant startAt) {
		if (!isTimeUnit(deadline) || !deadline.isAfter(now) || deadline.isAfter(startAt)) {
			throw new InvalidVoteDeadlineException();
		}
		return deadline;
	}

	/** 없으면 정원 없음(null). 1 이상. 아니면 InvalidCapacityException. */
	public static Integer requireValidCapacity(Integer capacity) {
		if (capacity != null && capacity < 1) {
			throw new InvalidCapacityException();
		}
		return capacity;
	}

	private static Instant endOf(Instant startAt, int durationMinutes) {
		return startAt.plus(Duration.ofMinutes(durationMinutes));
	}

	private static boolean isTimeUnit(Instant instant) {
		return instant.getNano() == 0 && Math.floorMod(instant.getEpochSecond(), TIME_UNIT_SECONDS) == 0;
	}

	private static int codePoints(String text) {
		return text.codePointCount(0, text.length());
	}

}
