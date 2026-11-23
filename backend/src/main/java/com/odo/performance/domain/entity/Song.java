package com.odo.performance.domain.entity;

import java.text.Normalizer;
import java.time.Instant;
import java.util.regex.Pattern;

import com.odo.performance.domain.exception.InvalidSongNameException;
import com.odo.performance.domain.exception.NotSongDirectorException;
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
 * 공연의 곡. 만든 디렉터가 담당이다. 삭제하면 행을 지우지 않고 deletedAt을 적는다.
 * 곡 이름 규칙은 Song에서만 쓰므로 이 클래스 안에 둔다. 규칙은 06_Performance_spec.md의 "곡 이름의 글자 규칙".
 * 이름 수정과 삭제는 조건부 UPDATE(SongRepository)로 하므로, 읽어 둔 엔티티의 값을 바꾸지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "song")
public class Song {

	/** 곡 이름은 앞뒤 공백을 뺀 뒤 코드 포인트로 1~100자. */
	private static final int NAME_MAX_LENGTH = 100;

	/**
	 * 곡 이름에 들어갈 수 없는 글자. 하나라도 있으면 거부한다.
	 * 기본으로 그림 문자로 보이는 이모지, 피부색 수식자, 이모지 표시 선택자(U+FE0F), 키캡(U+20E3), 국기(지역 표시 문자),
	 * 제어 문자, 서식 문자(ZWJ 포함), 줄과 문단 구분. \p{IsEmoji}는 숫자, #, *도 포함하므로 쓰지 않는다.
	 */
	private static final Pattern FORBIDDEN = Pattern.compile(
			"[\\p{IsEmoji_Presentation}\\p{IsEmoji_Modifier}\\x{FE0F}\\x{20E3}\\x{1F1E6}-\\x{1F1FF}\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "performance_id", nullable = false)
	private Long performanceId;

	@Column(name = "director_account_id", nullable = false)
	private Long directorAccountId;

	@Column(nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	private Song(long performanceId, long directorAccountId, String name, Instant now) {
		this.performanceId = performanceId;
		this.directorAccountId = directorAccountId;
		this.name = name;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** 규칙에 맞지 않으면 InvalidSongNameException. 만든 사람이 담당 디렉터다. */
	public static Song createSong(long performanceId, long directorAccountId, String name, Instant now) {
		return new Song(performanceId, directorAccountId, requireValidName(name), now);
	}

	/**
	 * 규칙에 맞으면 저장할 값(NFC로 합치고 앞뒤 공백을 뺀 값)을 돌려준다. 아니면 InvalidSongNameException.
	 * 이름 수정이 조건부 UPDATE로 바꿀 값을 얻을 때 쓴다.
	 */
	public static String requireValidName(String name) {
		String normalized = normalizeName(name);
		if (!isValidName(normalized)) {
			throw new InvalidSongNameException();
		}
		return normalized;
	}

	public boolean isDirectedBy(long accountId) {
		return directorAccountId == accountId;
	}

	/**
	 * 담당 디렉터가 아니면 NotSongDirectorException. 지금 역할이 디렉터인지는 보지 않는다.
	 * 이름 수정과 삭제는 조건부 UPDATE라 이 검사를 담을 수정 메서드가 없어 따로 둔다.
	 */
	public void requireDirectedBy(long accountId) {
		if (!isDirectedBy(accountId)) {
			throw new NotSongDirectorException();
		}
	}

	/** NFC로 합치고 앞뒤 공백을 뺀 값. null은 null. */
	private static String normalizeName(String name) {
		return name == null ? null : Normalizer.normalize(name, Normalizer.Form.NFC).strip();
	}

	/** PostgreSQL의 varchar(100)과 같은 단위(코드 포인트)로 센다. */
	private static boolean isValidName(String normalized) {
		if (normalized == null || normalized.isEmpty()) {
			return false;
		}
		return normalized.codePointCount(0, normalized.length()) <= NAME_MAX_LENGTH
				&& !FORBIDDEN.matcher(normalized).find();
	}

}
