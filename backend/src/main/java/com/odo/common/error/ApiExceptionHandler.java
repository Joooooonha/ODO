package com.odo.common.error;

import java.sql.SQLTransientConnectionException;

import com.odo.account.domain.exception.InvalidNameException;
import com.odo.performance.domain.exception.AlreadyInPerformanceException;
import com.odo.performance.domain.exception.DuplicateMemberException;
import com.odo.performance.domain.exception.EmptyMembersException;
import com.odo.performance.domain.exception.InvalidPerformanceDescriptionException;
import com.odo.performance.domain.exception.InvalidPerformanceNameException;
import com.odo.performance.domain.exception.InvalidRoleException;
import com.odo.performance.domain.exception.InvalidSongNameException;
import com.odo.performance.domain.exception.LeaderNotInMembersException;
import com.odo.performance.domain.exception.NotDirectorException;
import com.odo.performance.domain.exception.NotSongDirectorException;
import com.odo.performance.domain.exception.NotTeamMemberException;
import com.odo.performance.domain.exception.PerformanceMemberNotFoundException;
import com.odo.performance.domain.exception.PerformanceNotFoundException;
import com.odo.performance.domain.exception.SongNotFoundException;
import com.odo.schedule.domain.exception.CapacityBelowConfirmedException;
import com.odo.schedule.domain.exception.InvalidCapacityException;
import com.odo.schedule.domain.exception.InvalidDurationException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomAddressException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomMemoException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomNameException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomPasswordException;
import com.odo.schedule.domain.exception.InvalidRentalFeeException;
import com.odo.schedule.domain.exception.InvalidScheduleMemoException;
import com.odo.schedule.domain.exception.InvalidScheduleTitleException;
import com.odo.schedule.domain.exception.InvalidStartAtException;
import com.odo.schedule.domain.exception.InvalidStatusException;
import com.odo.schedule.domain.exception.InvalidVoteDeadlineException;
import com.odo.schedule.domain.exception.NotVotingScheduleException;
import com.odo.schedule.domain.exception.ParticipantNotFoundException;
import com.odo.schedule.domain.exception.PracticeRoomNotFoundException;
import com.odo.schedule.domain.exception.ScheduleEndedException;
import com.odo.schedule.domain.exception.ScheduleNotFoundException;
import com.odo.schedule.domain.exception.VotingClosedException;
import com.odo.team.domain.exception.AlreadyMemberException;
import com.odo.team.domain.exception.AlreadyRequestedException;
import com.odo.team.domain.exception.InvalidGenerationException;
import com.odo.team.domain.exception.InvalidTeamDescriptionException;
import com.odo.team.domain.exception.InvalidTeamNameException;
import com.odo.team.domain.exception.InviteNotFoundException;
import com.odo.team.domain.exception.NotTeamLeaderException;
import com.odo.team.domain.exception.RequestNotFoundException;
import com.odo.team.domain.exception.RequestNotPendingException;
import com.odo.team.domain.exception.TeamNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 도메인 예외를 HTTP 상태와 오류 코드로 바꾼다. 모든 API가 같은 오류 형식(code, message)을 쓴다.
 * Spring MVC가 요청을 읽지 못한 예외도 같은 형식으로 바꾼다. 그대로 두면 code 없이 Boot의 기본 오류 형식으로 나간다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	// 400 요청을 읽을 수 없음(본문 없음, JSON 형식 오류, 값이나 경로 변수의 타입이 틀림, 꼭 있어야 할 값이 없음)
	// message는 고정 문구다. 예외 메시지에는 파싱이 실패한 위치나 내부 클래스 이름이 들어간다.

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			InvalidRequestException.class })
	ResponseEntity<ErrorResponse> invalidRequest(Exception ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청을 읽을 수 없다");
	}

	// 400 입력 규칙 위반

	@ExceptionHandler(InvalidNameException.class)
	ResponseEntity<ErrorResponse> invalidName(InvalidNameException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_NAME", ex);
	}

	@ExceptionHandler(InvalidTeamNameException.class)
	ResponseEntity<ErrorResponse> invalidTeamName(InvalidTeamNameException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_TEAM_NAME", ex);
	}

	@ExceptionHandler(InvalidTeamDescriptionException.class)
	ResponseEntity<ErrorResponse> invalidTeamDescription(InvalidTeamDescriptionException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_TEAM_DESCRIPTION", ex);
	}

	@ExceptionHandler(InvalidGenerationException.class)
	ResponseEntity<ErrorResponse> invalidGeneration(InvalidGenerationException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_GENERATION", ex);
	}

	@ExceptionHandler(InvalidPerformanceNameException.class)
	ResponseEntity<ErrorResponse> invalidPerformanceName(InvalidPerformanceNameException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_PERFORMANCE_NAME", ex);
	}

	@ExceptionHandler(InvalidPerformanceDescriptionException.class)
	ResponseEntity<ErrorResponse> invalidPerformanceDescription(InvalidPerformanceDescriptionException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_PERFORMANCE_DESCRIPTION", ex);
	}

	@ExceptionHandler(InvalidSongNameException.class)
	ResponseEntity<ErrorResponse> invalidSongName(InvalidSongNameException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_SONG_NAME", ex);
	}

	@ExceptionHandler(LeaderNotInMembersException.class)
	ResponseEntity<ErrorResponse> leaderNotInMembers(LeaderNotInMembersException ex) {
		return error(HttpStatus.BAD_REQUEST, "LEADER_NOT_IN_MEMBERS", ex);
	}

	@ExceptionHandler(DuplicateMemberException.class)
	ResponseEntity<ErrorResponse> duplicateMember(DuplicateMemberException ex) {
		return error(HttpStatus.BAD_REQUEST, "DUPLICATE_MEMBER", ex);
	}

	@ExceptionHandler(NotTeamMemberException.class)
	ResponseEntity<ErrorResponse> notTeamMember(NotTeamMemberException ex) {
		return error(HttpStatus.BAD_REQUEST, "NOT_TEAM_MEMBER", ex);
	}

	@ExceptionHandler(EmptyMembersException.class)
	ResponseEntity<ErrorResponse> emptyMembers(EmptyMembersException ex) {
		return error(HttpStatus.BAD_REQUEST, "EMPTY_MEMBERS", ex);
	}

	@ExceptionHandler(InvalidRoleException.class)
	ResponseEntity<ErrorResponse> invalidRole(InvalidRoleException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_ROLE", ex);
	}

	// 403 권한

	@ExceptionHandler(NotTeamLeaderException.class)
	ResponseEntity<ErrorResponse> notTeamLeader(NotTeamLeaderException ex) {
		return error(HttpStatus.FORBIDDEN, "NOT_TEAM_LEADER", ex);
	}

	@ExceptionHandler(NotDirectorException.class)
	ResponseEntity<ErrorResponse> notDirector(NotDirectorException ex) {
		return error(HttpStatus.FORBIDDEN, "NOT_DIRECTOR", ex);
	}

	@ExceptionHandler(NotSongDirectorException.class)
	ResponseEntity<ErrorResponse> notSongDirector(NotSongDirectorException ex) {
		return error(HttpStatus.FORBIDDEN, "NOT_SONG_DIRECTOR", ex);
	}

	// 404 없음(팀원이 아닌 팀, 명단에 없는 공연과 곡도 없는 것으로 본다)

	@ExceptionHandler(TeamNotFoundException.class)
	ResponseEntity<ErrorResponse> teamNotFound(TeamNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", ex);
	}

	@ExceptionHandler(InviteNotFoundException.class)
	ResponseEntity<ErrorResponse> inviteNotFound(InviteNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", ex);
	}

	@ExceptionHandler(RequestNotFoundException.class)
	ResponseEntity<ErrorResponse> requestNotFound(RequestNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", ex);
	}

	@ExceptionHandler(PerformanceNotFoundException.class)
	ResponseEntity<ErrorResponse> performanceNotFound(PerformanceNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, "PERFORMANCE_NOT_FOUND", ex);
	}

	@ExceptionHandler(PerformanceMemberNotFoundException.class)
	ResponseEntity<ErrorResponse> performanceMemberNotFound(PerformanceMemberNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, "PERFORMANCE_MEMBER_NOT_FOUND", ex);
	}

	@ExceptionHandler(SongNotFoundException.class)
	ResponseEntity<ErrorResponse> songNotFound(SongNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, "SONG_NOT_FOUND", ex);
	}

	// 409 현재 상태와 맞지 않음

	@ExceptionHandler(AlreadyMemberException.class)
	ResponseEntity<ErrorResponse> alreadyMember(AlreadyMemberException ex) {
		return error(HttpStatus.CONFLICT, "ALREADY_MEMBER", ex);
	}

	@ExceptionHandler(AlreadyRequestedException.class)
	ResponseEntity<ErrorResponse> alreadyRequested(AlreadyRequestedException ex) {
		return error(HttpStatus.CONFLICT, "ALREADY_REQUESTED", ex);
	}

	@ExceptionHandler(RequestNotPendingException.class)
	ResponseEntity<ErrorResponse> requestNotPending(RequestNotPendingException ex) {
		return error(HttpStatus.CONFLICT, "REQUEST_NOT_PENDING", ex);
	}

	@ExceptionHandler(AlreadyInPerformanceException.class)
	ResponseEntity<ErrorResponse> alreadyInPerformance(AlreadyInPerformanceException ex) {
		return error(HttpStatus.CONFLICT, "ALREADY_IN_PERFORMANCE", ex);
	}

	// 일정과 연습실(03_Schedule_spec.md). 400 입력 규칙 위반과 본문 값 오류

	@ExceptionHandler(InvalidScheduleTitleException.class)
	ResponseEntity<ErrorResponse> invalidScheduleTitle(InvalidScheduleTitleException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE_TITLE", ex);
	}

	@ExceptionHandler(InvalidScheduleMemoException.class)
	ResponseEntity<ErrorResponse> invalidScheduleMemo(InvalidScheduleMemoException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE_MEMO", ex);
	}

	@ExceptionHandler(InvalidStartAtException.class)
	ResponseEntity<ErrorResponse> invalidStartAt(InvalidStartAtException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_START_AT", ex);
	}

	@ExceptionHandler(InvalidDurationException.class)
	ResponseEntity<ErrorResponse> invalidDuration(InvalidDurationException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_DURATION", ex);
	}

	@ExceptionHandler(InvalidVoteDeadlineException.class)
	ResponseEntity<ErrorResponse> invalidVoteDeadline(InvalidVoteDeadlineException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_VOTE_DEADLINE", ex);
	}

	@ExceptionHandler(InvalidCapacityException.class)
	ResponseEntity<ErrorResponse> invalidCapacity(InvalidCapacityException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_CAPACITY", ex);
	}

	@ExceptionHandler(InvalidStatusException.class)
	ResponseEntity<ErrorResponse> invalidStatus(InvalidStatusException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_STATUS", ex);
	}

	@ExceptionHandler(NotVotingScheduleException.class)
	ResponseEntity<ErrorResponse> notVotingSchedule(NotVotingScheduleException ex) {
		return error(HttpStatus.BAD_REQUEST, "NOT_VOTING_SCHEDULE", ex);
	}

	@ExceptionHandler(InvalidPracticeRoomException.class)
	ResponseEntity<ErrorResponse> invalidPracticeRoom(InvalidPracticeRoomException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_PRACTICE_ROOM", ex);
	}

	@ExceptionHandler(InvalidPracticeRoomNameException.class)
	ResponseEntity<ErrorResponse> invalidPracticeRoomName(InvalidPracticeRoomNameException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_PRACTICE_ROOM_NAME", ex);
	}

	@ExceptionHandler(InvalidPracticeRoomAddressException.class)
	ResponseEntity<ErrorResponse> invalidPracticeRoomAddress(InvalidPracticeRoomAddressException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_PRACTICE_ROOM_ADDRESS", ex);
	}

	@ExceptionHandler(InvalidPracticeRoomPasswordException.class)
	ResponseEntity<ErrorResponse> invalidPracticeRoomPassword(InvalidPracticeRoomPasswordException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_PRACTICE_ROOM_PASSWORD", ex);
	}

	@ExceptionHandler(InvalidPracticeRoomMemoException.class)
	ResponseEntity<ErrorResponse> invalidPracticeRoomMemo(InvalidPracticeRoomMemoException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_PRACTICE_ROOM_MEMO", ex);
	}

	@ExceptionHandler(InvalidRentalFeeException.class)
	ResponseEntity<ErrorResponse> invalidRentalFee(InvalidRentalFeeException ex) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_RENTAL_FEE", ex);
	}

	/** 공연과 같은 코드지만 일정이 공연 패키지에 의존하지 않도록 일정 패키지에 따로 둔 예외다. */
	@ExceptionHandler(com.odo.schedule.domain.exception.DuplicateMemberException.class)
	ResponseEntity<ErrorResponse> duplicateRequiredMember(RuntimeException ex) {
		return error(HttpStatus.BAD_REQUEST, "DUPLICATE_MEMBER", ex);
	}

	/** 공연과 같은 코드지만 일정이 공연 패키지에 의존하지 않도록 일정 패키지에 따로 둔 예외다. */
	@ExceptionHandler(com.odo.schedule.domain.exception.NotTeamMemberException.class)
	ResponseEntity<ErrorResponse> requiredNotTeamMember(RuntimeException ex) {
		return error(HttpStatus.BAD_REQUEST, "NOT_TEAM_MEMBER", ex);
	}

	// 일정과 연습실. 404 없거나 볼 수 없음

	@ExceptionHandler(ScheduleNotFoundException.class)
	ResponseEntity<ErrorResponse> scheduleNotFound(ScheduleNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", ex);
	}

	@ExceptionHandler(PracticeRoomNotFoundException.class)
	ResponseEntity<ErrorResponse> practiceRoomNotFound(PracticeRoomNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, "PRACTICE_ROOM_NOT_FOUND", ex);
	}

	@ExceptionHandler(ParticipantNotFoundException.class)
	ResponseEntity<ErrorResponse> participantNotFound(ParticipantNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", ex);
	}

	// 일정. 409 자원의 지금 상태와 충돌

	@ExceptionHandler(ScheduleEndedException.class)
	ResponseEntity<ErrorResponse> scheduleEnded(ScheduleEndedException ex) {
		return error(HttpStatus.CONFLICT, "SCHEDULE_ENDED", ex);
	}

	@ExceptionHandler(VotingClosedException.class)
	ResponseEntity<ErrorResponse> votingClosed(VotingClosedException ex) {
		return error(HttpStatus.CONFLICT, "VOTING_CLOSED", ex);
	}

	@ExceptionHandler(CapacityBelowConfirmedException.class)
	ResponseEntity<ErrorResponse> capacityBelowConfirmed(CapacityBelowConfirmedException ex) {
		return error(HttpStatus.CONFLICT, "CAPACITY_BELOW_CONFIRMED", ex);
	}

	// 503 서버가 바쁨. DB 연결을 대기 시간(HikariCP connection-timeout) 안에 받지 못했다. 잠시 뒤 다시 보내면 된다.
	// 원인이 연결 대기 초과(SQLTransientConnectionException)일 때만 바꾸고, 다른 원인은 다시 던져 지금처럼 500이 되게 한다.

	@ExceptionHandler({ CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class })
	ResponseEntity<ErrorResponse> serverBusy(RuntimeException ex) {
		if (!causedBy(ex, SQLTransientConnectionException.class)) {
			throw ex;
		}
		return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_BUSY", "서버가 바쁘다. 잠시 뒤 다시 보낸다");
	}

	private static boolean causedBy(Throwable ex, Class<? extends Throwable> type) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (type.isInstance(cause)) {
				return true;
			}
		}
		return false;
	}

	private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, RuntimeException ex) {
		return error(status, code, ex.getMessage());
	}

	private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}

}
