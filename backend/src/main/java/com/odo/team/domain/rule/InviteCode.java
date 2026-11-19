package com.odo.team.domain.rule;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 초대 코드. 16바이트 난수를 패딩 없는 base64url로 바꾼 22자.
 * 추측으로 다른 팀의 코드를 맞힐 수 없고, 글자가 경로에 그대로 들어간다.
 */
public final class InviteCode {

	public static final int LENGTH = 22;

	private static final int RANDOM_BYTES = 16;

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private InviteCode() {
	}

	public static String generate() {
		byte[] bytes = new byte[RANDOM_BYTES];
		RANDOM.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}

}
