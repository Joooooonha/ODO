package com.odo.account.presentation;

import com.odo.account.application.AccountService;
import com.odo.account.presentation.dto.ChangeNameRequest;
import com.odo.account.presentation.dto.MeResponse;
import com.odo.auth.presentation.LoginAccountId;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class MeController {

	private final AccountService accountService;

	@GetMapping
	public MeResponse me(@LoginAccountId long accountId) {
		return MeResponse.from(accountService.getProfile(accountId));
	}

	@PatchMapping
	public MeResponse changeName(@LoginAccountId long accountId, @RequestBody ChangeNameRequest request) {
		return MeResponse.from(accountService.changeName(accountId, request.name()));
	}

}
