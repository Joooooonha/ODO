package com.odo.account;

import java.time.Clock;

import com.odo.common.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
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

	private final AccountRepository accountRepository;

	private final Clock clock;

	public MeController(AccountRepository accountRepository, Clock clock) {
		this.accountRepository = accountRepository;
		this.clock = clock;
	}

	@GetMapping
	@Transactional(readOnly = true)
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		return MeResponse.from(find(jwt));
	}

	@PatchMapping
	@Transactional
	public MeResponse changeName(@AuthenticationPrincipal Jwt jwt, @RequestBody ChangeNameRequest request) {
		Account account = find(jwt);
		account.changeName(request.name(), clock.instant());
		return MeResponse.from(account);
	}

	@ExceptionHandler(InvalidNameException.class)
	public ResponseEntity<ErrorResponse> invalidName(InvalidNameException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("INVALID_NAME", ex.getMessage()));
	}

	private Account find(Jwt jwt) {
		long accountId = Long.parseLong(jwt.getSubject());
		// 탈퇴가 없으므로 서명이 맞은 토큰의 계정은 항상 있다
		return accountRepository.findById(accountId)
			.orElseThrow(() -> new IllegalStateException("토큰의 계정이 없다: " + accountId));
	}

	public record MeResponse(long id, String name) {

		static MeResponse from(Account account) {
			return new MeResponse(account.getId(), account.getName());
		}

	}

	public record ChangeNameRequest(String name) {
	}

}
