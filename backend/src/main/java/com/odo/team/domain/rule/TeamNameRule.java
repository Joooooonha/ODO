package com.odo.team.domain.rule;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 팀 이름 규칙. 02_TeamSpace_spec.md의 "팀 정보".
 * 앞뒤 공백을 뺀 뒤 한글 완성 글자(가-힣), 영문, 숫자, 공백으로 된 1~30자. 다른 팀과 같아도 된다.
 */
public final class TeamNameRule {

	public static final int MAX_LENGTH = 30;

	private static final Pattern VALID = Pattern.compile("^[가-힣a-zA-Z0-9 ]{1," + MAX_LENGTH + "}$");

	private TeamNameRule() {
	}

	/** NFC로 합치고 앞뒤 공백을 뺀 값. null은 null. */
	public static String normalize(String name) {
		return name == null ? null : Normalizer.normalize(name, Normalizer.Form.NFC).strip();
	}

	/** 정규화한 값이 규칙에 맞는지. 남은 글자는 모두 BMP 문자라 정규식의 글자 수가 코드 포인트 수와 같다. */
	public static boolean isValid(String name) {
		return name != null && VALID.matcher(normalize(name)).matches();
	}

}
