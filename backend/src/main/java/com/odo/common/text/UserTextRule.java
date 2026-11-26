package com.odo.common.text;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 사용자가 입력해 다른 사람에게 보이는 글자의 공통 규칙. 길이와 오류 코드는 각 엔티티가 정한다.
 * 막는 글자는 곡 이름 규칙(Song)과 같다. 규칙은 03_Schedule_spec.md의 "입력 검증과 글자 규칙".
 * 글자 종류를 막지 않는 코멘트의 셈과 확인은 08_PracticeVideo_spec.md의 "입력 검증과 글자 규칙".
 */
public final class UserTextRule {

	/**
	 * 들어갈 수 없는 글자. 기본으로 그림 문자로 보이는 이모지, 피부색 수식자, 이모지 표시 선택자(U+FE0F), 키캡(U+20E3),
	 * 국기(지역 표시 문자), 제어 문자(줄바꿈과 탭 포함), 서식 문자(ZWJ, 폭 없는 공백 포함), 줄과 문단 구분.
	 * \p{IsEmoji}는 숫자, #, *도 포함하므로 쓰지 않는다.
	 */
	private static final Pattern FORBIDDEN = Pattern.compile(
			"[\\p{IsEmoji_Presentation}\\p{IsEmoji_Modifier}\\x{FE0F}\\x{20E3}\\x{1F1E6}-\\x{1F1FF}\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

	/** 줄바꿈(\n)을 뺀 제어 문자. 서식 문자(ZWJ, 표시 선택자)는 \p{Cf}라 들어가지 않는다. */
	private static final Pattern CONTROL_EXCEPT_NEWLINE = Pattern.compile("[\\p{Cc}&&[^\\n]]");

	private UserTextRule() {
	}

	/** NFC로 합치고 앞뒤 공백을 뺀 값. null이면 null이다. */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		return Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
	}

	/** 제목이나 이름처럼 한 줄인 글자에 이모지, 제어 문자, 서식 문자, 줄바꿈이 있는지. */
	public static boolean containsForbiddenCharForSingleLine(String text) {
		return FORBIDDEN.matcher(text).find();
	}

	/** 메모처럼 여러 줄인 글자. 줄바꿈(\n)은 허용하고, \r\n은 \n으로 보고 확인한다. 나머지는 한 줄 글자와 같다. */
	public static boolean containsForbiddenCharForMultiLine(String text) {
		String lines = text.replace("\r\n", "\n");
		return FORBIDDEN.matcher(lines.replace("\n", "")).find();
	}

	/**
	 * 보이는 글자 수. 확장 문자소 단위라 👨‍👩‍👧, 🇰🇷, 👍🏽은 각각 1자다. 화면의 Intl.Segmenter와 같은 방식이다.
	 * Java 20부터 BreakIterator의 글자 경계가 유니코드의 확장 문자소 규칙을 따른다.
	 */
	public static int countGraphemes(String text) {
		BreakIterator boundaries = BreakIterator.getCharacterInstance(Locale.ROOT);
		boundaries.setText(text);
		int count = 0;
		while (boundaries.next() != BreakIterator.DONE) {
			count++;
		}
		return count;
	}

	/** 줄바꿈(\n)을 뺀 제어 문자가 있는지. \r\n은 \n으로 보고 확인한다. 글자 종류를 막지 않는 코멘트가 쓴다. */
	public static boolean containsControlCharExceptNewline(String text) {
		return CONTROL_EXCEPT_NEWLINE.matcher(text.replace("\r\n", "\n")).find();
	}

}
