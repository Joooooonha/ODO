package com.odo.notification.presentation;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.common.error.InvalidRequestException;
import com.odo.notification.application.stream.NotificationStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실시간 연결(SSE). 11_Notification_spec.md의 "실시간 연결".
 * 화면은 fetch로 읽어 Authorization 헤더를 붙인다. 다른 API와 같은 JWT 인증이다.
 */
@RestController
@RequiredArgsConstructor
public class NotificationStreamController {

	private final NotificationStreamService streamService;

	/** Last-Event-ID가 숫자가 아니면 INVALID_REQUEST, 음수면 INVALID_NOTIFICATION_SEQ다. 둘 다 연결을 열기 전에 답한다. */
	@GetMapping("/api/notifications/stream")
	public SseEmitter stream(@LoginAccountId long accountId, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
		return streamService.open(accountId, parse(lastEventId), jwt.getExpiresAt());
	}

	private static Long parse(String lastEventId) {
		if (lastEventId == null) {
			return null;
		}
		try {
			return Long.valueOf(lastEventId.trim());
		}
		catch (NumberFormatException ex) {
			throw new InvalidRequestException();
		}
	}

}
