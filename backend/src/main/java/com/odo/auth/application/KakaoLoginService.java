package com.odo.auth.application;

import java.time.Clock;
import java.time.Instant;

import com.odo.account.domain.entity.Account;
import com.odo.account.domain.repository.AccountRepository;
import com.odo.auth.domain.entity.SocialLogin;
import com.odo.auth.domain.repository.SocialLoginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 카카오 회원번호로 계정을 찾거나, 없으면 정리한 닉네임으로 만든다.
 * <p>
 * 같은 사람이 동시에 처음 로그인하면 두 요청이 모두 "없음"을 보고 만들려 한다.
 * social_login의 유니크 제약에서 나중 요청이 실패하므로, 그 트랜잭션을 되돌리고 먼저 만들어진 계정으로 로그인시킨다.
 */
@Service
@RequiredArgsConstructor
public class KakaoLoginService {

	private final SocialLoginRepository socialLoginRepository;

	private final AccountRepository accountRepository;

	private final TransactionTemplate transactionTemplate;

	private final Clock clock;

	public LoginResult login(String kakaoUserId, String nickname) {
		var existing = findAccountId(kakaoUserId);
		if (existing != null) {
			return new LoginResult(existing, false);
		}
		try {
			Long created = transactionTemplate.execute(status -> signUp(kakaoUserId, nickname));
			return new LoginResult(created, true);
		}
		catch (DataIntegrityViolationException ex) {
			Long winner = findAccountId(kakaoUserId);
			if (winner == null) {
				throw ex;
			}
			return new LoginResult(winner, false);
		}
	}

	private Long signUp(String kakaoUserId, String nickname) {
		Instant now = clock.instant();
		Account account = accountRepository.save(Account.signUpWithNickname(nickname, now));
		socialLoginRepository.save(new SocialLogin(account.getId(), SocialLogin.KAKAO, kakaoUserId, now));
		return account.getId();
	}

	private Long findAccountId(String kakaoUserId) {
		return socialLoginRepository.findByProviderAndProviderUserId(SocialLogin.KAKAO, kakaoUserId)
			.map(SocialLogin::getAccountId)
			.orElse(null);
	}

	public record LoginResult(long accountId, boolean newAccount) {
	}

}
