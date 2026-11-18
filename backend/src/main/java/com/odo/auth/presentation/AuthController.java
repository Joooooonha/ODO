package com.odo.auth.presentation;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.auth.application.RefreshTokenService.RefreshFailedException;
import com.odo.auth.application.RefreshTokenService.RefreshResult;
import com.odo.auth.application.RefreshTokenService;
import com.odo.auth.presentation.dto.RefreshResponse;
import com.odo.common.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 갱신과 로그아웃. 둘 다 접근 토큰 없이 갱신 토큰 쿠키만으로 동작한다.
 */
@RestController
@RequestMapping(RefreshTokenCookies.PATH)
public class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final RefreshTokenService refreshTokenService;

	public AuthController(RefreshTokenService refreshTokenService) {
		this.refreshTokenService = refreshTokenService;
	}

	@PostMapping("/refresh")
	public ResponseEntity<?> refresh(@CookieValue(name = RefreshTokenCookies.NAME, required = false) String token) {
		RefreshResult result;
		try {
			result = refreshTokenService.refresh(token);
		}
		catch (RefreshFailedException ex) {
			// 실패 이유는 로그에만 남기고, 프론트에는 하나의 코드로 알린다
			log.info("갱신 실패: {}", ex.getMessage());
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.SET_COOKIE, RefreshTokenCookies.delete().toString())
				.body(new ErrorResponse("REFRESH_FAILED", "다시 로그인해야 한다"));
		}
		ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
		if (result.newRefreshToken() != null) {
			ok.header(HttpHeaders.SET_COOKIE, RefreshTokenCookies.issue(result.newRefreshToken()).toString());
		}
		return ok.body(new RefreshResponse(result.accessToken(), AccessTokenIssuer.TTL.toSeconds()));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@CookieValue(name = RefreshTokenCookies.NAME, required = false) String token) {
		refreshTokenService.logout(token);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshTokenCookies.delete().toString()).build();
	}

}
