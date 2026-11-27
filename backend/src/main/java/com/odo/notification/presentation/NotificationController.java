package com.odo.notification.presentation;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.common.error.InvalidRequestException;
import com.odo.notification.application.NotificationService;
import com.odo.notification.presentation.dto.NotificationPageResponse;
import com.odo.notification.presentation.dto.OpenedRequest;
import com.odo.notification.presentation.dto.UnseenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 알림함. 11_Notification_spec.md의 API 명세. 알림함은 계정마다 하나라 팀이나 공연 아래에 두지 않는다.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationService notificationService;

	@GetMapping
	public NotificationPageResponse list(@LoginAccountId long accountId,
			@RequestParam(required = false) Long cursor) {
		return NotificationPageResponse.from(notificationService.findPage(accountId, cursor));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@LoginAccountId long accountId, @PathVariable long id) {
		notificationService.delete(accountId, id);
	}

	/** 지울 것이 없어도 결과(그 순번까지 비어 있음)가 같아 204다. upTo가 없으면 INVALID_REQUEST다. */
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void clear(@LoginAccountId long accountId, @RequestParam(required = false) Long upTo) {
		if (upTo == null) {
			throw new InvalidRequestException();
		}
		notificationService.clear(accountId, upTo);
	}

	@GetMapping("/unseen")
	public UnseenResponse unseen(@LoginAccountId long accountId) {
		return new UnseenResponse(notificationService.hasNew(accountId));
	}

	/** "내가 본 위치"를 두는 요청이라 같은 값을 여러 번 보내도 결과가 같다(PUT). */
	@PutMapping("/opened")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void opened(@LoginAccountId long accountId, @RequestBody OpenedRequest request) {
		notificationService.markOpened(accountId, request.requireSeq());
	}

}
