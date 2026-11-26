package com.odo.practicevideo.domain.entity;

import java.time.Instant;

import com.odo.common.text.UserTextRule;
import com.odo.practicevideo.domain.exception.InvalidCommentContentException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 스레드의 코멘트. 08_PracticeVideo_spec.md의 "시점 코멘트", "입력 검증과 글자 규칙".
 * 영상 ID도 함께 두어 영상의 코멘트를 스레드와 조인하지 않고 읽는다. DB의 복합 FK가 스레드의 영상과 맞춘다.
 * 1차에는 수정과 삭제가 없다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "practice_video_comment")
public class PracticeVideoComment {

	/** 보이는 글자(확장 문자소)로 300자까지. */
	private static final int CONTENT_MAX_GRAPHEMES = 300;

	/**
	 * 저장 길이(UTF-16 단위)로 5,000까지. 결합 문자는 몇 개를 붙여도 보이는 글자로 1자라, 보이는 글자만 세면 크기에 상한이 없다.
	 * 흔한 이모지 중 가장 긴 것(15)이 300개여도 4,500이다.
	 */
	static final int CONTENT_MAX_LENGTH = 5_000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "thread_id", nullable = false)
	private Long threadId;

	@Column(name = "video_id", nullable = false)
	private Long videoId;

	@Column(name = "author_account_id", nullable = false)
	private Long authorAccountId;

	@Column(nullable = false)
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	private PracticeVideoComment(long threadId, long videoId, long authorAccountId, String content, Instant now) {
		this.threadId = threadId;
		this.videoId = videoId;
		this.authorAccountId = authorAccountId;
		this.content = content;
		this.createdAt = now;
	}

	/** 스레드에 코멘트를 단다. 영상은 스레드의 영상이다. 내용이 규칙에 맞지 않으면 InvalidCommentContentException. */
	public static PracticeVideoComment write(PracticeVideoThread thread, long authorAccountId, String content,
			Instant now) {
		return new PracticeVideoComment(thread.getId(), thread.getVideoId(), authorAccountId,
				requireValidContent(content), now);
	}

	/**
	 * NFC로 합치고 앞뒤 공백을 뺀 뒤 보이는 글자로 1~300자, 저장 길이 5,000까지. 글자 종류는 막지 않고, 줄바꿈을 뺀 제어 문자만 막는다.
	 * 저장할 값을 돌려준다. 아니면 InvalidCommentContentException.
	 * 저장 길이를 먼저 확인해 큰 문자열을 끝까지 세지 않는다.
	 */
	public static String requireValidContent(String content) {
		String normalized = UserTextRule.normalize(content);
		if (normalized == null || normalized.isEmpty() || normalized.length() > CONTENT_MAX_LENGTH
				|| UserTextRule.containsControlCharExceptNewline(normalized)
				|| UserTextRule.countGraphemes(normalized) > CONTENT_MAX_GRAPHEMES) {
			throw new InvalidCommentContentException();
		}
		return normalized;
	}

}
