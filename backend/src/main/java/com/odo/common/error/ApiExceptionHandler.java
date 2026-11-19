package com.odo.common.error;

import com.odo.account.domain.InvalidNameException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 도메인 예외를 HTTP 상태와 오류 코드로 바꾼다. 모든 API가 같은 오류 형식(code, message)을 쓴다.
 * Spring MVC가 요청을 읽지 못한 예외도 같은 형식으로 바꾼다. 그대로 두면 code 없이 Boot의 기본 오류 형식으로 나간다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	// 400 요청을 읽을 수 없음(본문 없음, JSON 형식 오류, 값이나 경로 변수의 타입이 틀림)
	// message는 고정 문구다. 예외 메시지에는 파싱이 실패한 위치나 내부 클래스 이름이 들어간다.

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
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

	// 403 권한

	@ExceptionHandler(NotTeamLeaderException.class)
	ResponseEntity<ErrorResponse> notTeamLeader(NotTeamLeaderException ex) {
		return error(HttpStatus.FORBIDDEN, "NOT_TEAM_LEADER", ex);
	}

	// 404 없음(팀원이 아닌 팀도 없는 것으로 본다)

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

	private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, RuntimeException ex) {
		return error(status, code, ex.getMessage());
	}

	private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message));
	}

}
