package com.odo.performance.domain.entity;

import java.text.Normalizer;
import java.time.Instant;
import java.util.regex.Pattern;

import com.odo.performance.domain.exception.InvalidPerformanceDescriptionException;
import com.odo.performance.domain.exception.InvalidPerformanceNameException;
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
 * 공연. 이름과 설명은 항상 규칙을 지킨 값이다. 명단은 PerformanceMember, 곡은 Song이 나타낸다.
 * 이름과 설명 규칙은 Performance에서만 쓰므로 이 클래스 안에 둔다. 규칙은 06_Performance_spec.md의 "데이터".
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "performance")
public class Performance {

	/** 공연 이름은 앞뒤 공백을 뺀 뒤 1~100자. */
	private static final int NAME_MAX_LENGTH = 100;

	/** 한글 완성 글자(가-힣), 영문, 숫자, 공백. 팀 이름과 같은 글자 종류다. */
	private static final Pattern NAME_PATTERN = Pattern.compile("^[가-힣a-zA-Z0-9 ]{1," + NAME_MAX_LENGTH + "}$");

	/** 공연 설명은 선택이고 글자 종류는 제한하지 않는다. 앞뒤 공백을 포함해 코드 포인트로 100자까지. */
	private static final int DESCRIPTION_MAX_LENGTH = 100;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Column(nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Column(length = DESCRIPTION_MAX_LENGTH)
	private String description;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private Performance(long teamId, String name, String description, Instant now) {
		this.teamId = teamId;
		this.name = name;
		this.description = description;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * 규칙에 맞지 않으면 InvalidPerformanceNameException, InvalidPerformanceDescriptionException.
	 * 이름은 앞뒤 공백을 빼고, 설명은 그대로 NFC로만 합쳐 저장한다.
	 */
	public static Performance createPerformance(long teamId, String name, String description, Instant now) {
		validate(name, description);
		return new Performance(teamId, normalizeName(name), normalizeDescription(description), now);
	}

	/** 이름과 설명을 모두 바꾼다(PUT). 설명이 null이나 빈 문자열이면 비운다. 예외는 createPerformance와 같다. */
	public void updatePerformance(String name, String description, Instant now) {
		validate(name, description);
		this.name = normalizeName(name);
		this.description = normalizeDescription(description);
		this.updatedAt = now;
	}

	private static void validate(String name, String description) {
		if (!isValidName(name)) {
			throw new InvalidPerformanceNameException();
		}
		if (!isValidDescription(description)) {
			throw new InvalidPerformanceDescriptionException();
		}
	}

	/** NFC로 합치고 앞뒤 공백을 뺀 값. null은 null. */
	private static String normalizeName(String name) {
		return name == null ? null : Normalizer.normalize(name, Normalizer.Form.NFC).strip();
	}

	/** 정규화한 값이 규칙에 맞는지. 남은 글자는 모두 BMP 문자라 정규식의 글자 수가 코드 포인트 수와 같다. */
	private static boolean isValidName(String name) {
		return name != null && NAME_PATTERN.matcher(normalizeName(name)).matches();
	}

	/** NFC로 합친 값. 공백은 그대로 둔다. null과 빈 문자열은 설명 없음(null). */
	private static String normalizeDescription(String description) {
		if (description == null || description.isEmpty()) {
			return null;
		}
		return Normalizer.normalize(description, Normalizer.Form.NFC);
	}

	/** PostgreSQL의 varchar(100)과 같은 단위(코드 포인트)로 센다. */
	private static boolean isValidDescription(String description) {
		String normalized = normalizeDescription(description);
		return normalized == null || normalized.codePointCount(0, normalized.length()) <= DESCRIPTION_MAX_LENGTH;
	}

}
