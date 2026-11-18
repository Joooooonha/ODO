package com.odo.account.presentation;

import com.odo.account.application.AccountService;
import com.odo.account.domain.InvalidNameException;
import com.odo.account.presentation.dto.ChangeNameRequest;
import com.odo.account.presentation.dto.MeResponse;
import com.odo.common.error.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 정보 조회와 이름 변경. 가입 직후 이름 확인 화면과 프로필 수정이 같은 API를 쓴다.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

	private final AccountService accountService;

	public MeController(AccountService accountService) {
		this.accountService = accountService;
	}

	@GetMapping
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		return MeResponse.from(accountService.getProfile(accountId(jwt)));
	}

	@PatchMapping
	public MeResponse changeName(@AuthenticationPrincipal Jwt jwt, @RequestBody ChangeNameRequest request) {
		return MeResponse.from(accountService.changeName(accountId(jwt), request.name()));
	}

	@ExceptionHandler(InvalidNameException.class)
	public ResponseEntity<ErrorResponse> invalidName(InvalidNameException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_NAME", ex.getMessage()));
	}

	/** 접근 토큰의 sub가 계정 ID다. */
	private static long accountId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
