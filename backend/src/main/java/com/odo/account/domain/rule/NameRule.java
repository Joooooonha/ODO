package com.odo.account.domain.rule;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 이름 규칙과 카카오 닉네임 정리. 12_Account_spec.md의 "이름 규칙", "닉네임 정리".
 * 한글 완성 글자(가-힣)와 영문 대소문자만 쓴다. 가-힣 범위에는 자음과 모음(ㅋ, ㅏ)이 들어 있지 않다.
 */
public final class NameRule {

	public static final String DEFAULT_NAME = "사용자";

	public static final int MAX_LENGTH = 20;

	private static final Pattern VALID = Pattern.compile("^[가-힣a-zA-Z]{1," + MAX_LENGTH + "}$");

	private static final Pattern NOT_ALLOWED = Pattern.compile("[^가-힣a-zA-Z]");

	private NameRule() {
	}

	/**
	 * NFC로 정규화한 값을 돌려준다. null은 null.
	 * 일부 기기는 한글을 자모로 분해해(NFD) 보내므로, 검사와 저장 전에 합친다.
	 */
	public static String normalize(String name) {
		return name == null ? null : Normalizer.normalize(name, Normalizer.Form.NFC);
	}

	/** NFC 정규화 뒤 한글 완성 글자와 영문만으로 1~20자인지. */
	public static boolean isValid(String name) {
		return name != null && VALID.matcher(normalize(name)).matches();
	}

	/** 쓸 수 없는 글자를 빼고 20자로 자른다. 남는 게 없거나 null이면 DEFAULT_NAME. */
	public static String nameFromNickname(String nickname) {
		if (nickname == null) {
			return DEFAULT_NAME;
		}
		String cleaned = NOT_ALLOWED.matcher(normalize(nickname)).replaceAll("");
		if (cleaned.isEmpty()) {
			return DEFAULT_NAME;
		}
		// 남은 글자는 모두 BMP 문자라 length()가 글자 수와 같다
		return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
	}

}
