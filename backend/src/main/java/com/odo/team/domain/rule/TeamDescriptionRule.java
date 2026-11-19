package com.odo.team.domain.rule;

import java.text.Normalizer;

/**
 * 팀 소개 문구 규칙. 02_TeamSpace_spec.md의 "팀 정보".
 * 선택이고 글자 종류는 제한하지 않는다. 앞뒤 공백을 포함해 코드 포인트로 100자까지.
 */
public final class TeamDescriptionRule {

	public static final int MAX_LENGTH = 100;

	private TeamDescriptionRule() {
	}

	/** NFC로 합친 값. 공백은 그대로 둔다. null과 빈 문자열은 소개 없음(null). */
	public static String normalize(String description) {
		if (description == null || description.isEmpty()) {
			return null;
		}
		return Normalizer.normalize(description, Normalizer.Form.NFC);
	}

	/** PostgreSQL의 varchar(100)과 같은 단위(코드 포인트)로 센다. */
	public static boolean isValid(String description) {
		String normalized = normalize(description);
		return normalized == null || normalized.codePointCount(0, normalized.length()) <= MAX_LENGTH;
	}

}
