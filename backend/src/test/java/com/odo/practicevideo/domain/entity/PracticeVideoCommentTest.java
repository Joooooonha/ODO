package com.odo.practicevideo.domain.entity;

import java.text.Normalizer;

import com.odo.practicevideo.domain.exception.InvalidCommentContentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 코멘트 내용 규칙. 08_PracticeVideo_spec.md의 "입력 검증과 글자 규칙", "코멘트 (단위와 통합)".
 * NFC로 합치고 앞뒤 공백을 뺀 뒤 보이는 글자로 1~300자. 저장 길이(UTF-16)는 5,000까지다.
 * 글자 종류는 막지 않고, 줄바꿈을 뺀 제어 문자만 막는다.
 */
class PracticeVideoCommentTest {

	@Test
	@DisplayName("앞뒤 공백을 빼고 NFC로 합친 값을 돌려준다")
	void normalized() {
		assertThat(PracticeVideoComment.requireValidContent("  여기 팔 각도가 어떻게 돼요?  ")).isEqualTo("여기 팔 각도가 어떻게 돼요?");
		assertThat(PracticeVideoComment.requireValidContent(Normalizer.normalize("정면", Normalizer.Form.NFD)))
			.isEqualTo("정면");
	}

	@ParameterizedTest
	@ValueSource(strings = { "45도로 들어요 🙆", "👨‍👩‍👧 다 같이", "Korea 🇰🇷", "첫 줄\n둘째 줄", "첫 줄\r\n둘째 줄", "#1 (A) ♥️" })
	@DisplayName("이모지, 국기, 줄바꿈, \\r\\n, 기호를 받는다")
	void allowed(String content) {
		assertThat(PracticeVideoComment.requireValidContent(content)).isEqualTo(content);
	}

	@Test
	@DisplayName("보이는 글자로 300자까지 받는다. 이모지 300개는 받고 301개는 막는다")
	void length() {
		assertThat(PracticeVideoComment.requireValidContent("가".repeat(300))).hasSize(300);
		assertThat(PracticeVideoComment.requireValidContent("👍🏽".repeat(300))).isEqualTo("👍🏽".repeat(300));
		assertThat(PracticeVideoComment.requireValidContent("👨‍👩‍👧".repeat(300))).isEqualTo("👨‍👩‍👧".repeat(300));

		assertThatThrownBy(() -> PracticeVideoComment.requireValidContent("가".repeat(301)))
			.isInstanceOf(InvalidCommentContentException.class);
		assertThatThrownBy(() -> PracticeVideoComment.requireValidContent("👍🏽".repeat(301)))
			.isInstanceOf(InvalidCommentContentException.class);
	}

	/**
	 * 결합 문자는 몇 개를 붙여도 보이는 글자로 1자라, 보이는 글자만 세면 코멘트가 몇 MB가 될 수 있다(PR B 리뷰).
	 * x 뒤에 U+0301을 19개 붙이면 1자, 길이 20이다. a와 달리 x는 NFC로 합쳐지는 글자가 없어 길이가 그대로다.
	 */
	@Test
	@DisplayName("저장 길이는 5,000까지 받는다. 보이는 글자가 300자 안이어도 5,001이면 막는다")
	void storedLength() {
		String grapheme = "x" + "\u0301".repeat(19);
		String atLimit = grapheme.repeat(250);
		assertThat(atLimit).hasSize(5_000);

		assertThat(PracticeVideoComment.requireValidContent(atLimit)).isEqualTo(atLimit);
		assertThatThrownBy(() -> PracticeVideoComment.requireValidContent(atLimit + "\u0301"))
			.isInstanceOf(InvalidCommentContentException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "  \n  ", "\t" })
	@DisplayName("없거나, 비었거나, 공백만 있으면 막는다")
	void blank(String content) {
		assertThatThrownBy(() -> PracticeVideoComment.requireValidContent(content))
			.isInstanceOf(InvalidCommentContentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "NUL\u0000포함", "중간에\t탭", "중간에\r혼자", "벨\u0007" })
	@DisplayName("NUL과 줄바꿈이 아닌 제어 문자는 막는다")
	void controlChar(String content) {
		assertThatThrownBy(() -> PracticeVideoComment.requireValidContent(content))
			.isInstanceOf(InvalidCommentContentException.class);
	}

}
