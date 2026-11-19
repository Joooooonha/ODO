package com.odo.team.domain.rule;

import java.util.HashSet;
import java.util.Set;

import com.odo.auth.domain.RedirectPathValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InviteCodeTest {

	@Test
	@DisplayName("초대 코드는 base64url 글자로 된 22자다")
	void format() {
		assertThat(InviteCode.generate()).matches("^[A-Za-z0-9_-]{22}$");
	}

	@Test
	@DisplayName("만들 때마다 다른 코드가 나온다")
	void random() {
		Set<String> codes = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			codes.add(InviteCode.generate());
		}
		assertThat(codes).hasSize(1000);
	}

	@Test
	@DisplayName("초대 링크 경로는 로그인 뒤 돌아갈 주소 검사를 그대로 통과한다")
	void passesRedirectCheck() {
		for (int i = 0; i < 100; i++) {
			String path = "/invite/" + InviteCode.generate();
			assertThat(RedirectPathValidator.safePathOrDefault(path)).isEqualTo(path);
		}
	}

}
