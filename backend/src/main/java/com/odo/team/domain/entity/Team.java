package com.odo.team.domain.entity;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

import com.odo.team.domain.exception.InvalidTeamDescriptionException;
import com.odo.team.domain.exception.InvalidTeamNameException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 팀 스페이스. 이름과 소개 문구는 항상 규칙을 지킨 값이다. 팀장과 팀원은 TeamMember가 나타낸다.
 * 이름, 소개 문구, 초대 코드 규칙은 Team에서만 쓰므로 이 클래스 안에 둔다. 규칙은 02_TeamSpace_spec.md의 "팀 정보".
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "team")
public class Team {

	/** 팀 이름은 앞뒤 공백을 뺀 뒤 1~30자. */
	private static final int NAME_MAX_LENGTH = 30;

	/** 한글 완성 글자(가-힣), 영문, 숫자, 공백. 다른 팀과 같아도 된다. */
	private static final Pattern NAME_PATTERN = Pattern.compile("^[가-힣a-zA-Z0-9 ]{1," + NAME_MAX_LENGTH + "}$");

	/** 소개 문구는 선택이고 글자 종류는 제한하지 않는다. 앞뒤 공백을 포함해 코드 포인트로 100자까지. */
	private static final int DESCRIPTION_MAX_LENGTH = 100;

	/** 초대 코드는 16바이트 난수를 패딩 없는 base64url로 바꾼 22자. 추측으로 맞힐 수 없고 경로에 그대로 들어간다. */
	private static final int INVITE_CODE_LENGTH = 22;

	private static final int INVITE_CODE_RANDOM_BYTES = 16;

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Base64.Encoder INVITE_CODE_ENCODER = Base64.getUrlEncoder().withoutPadding();

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Column(length = DESCRIPTION_MAX_LENGTH)
	private String description;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "invite_code", nullable = false, length = INVITE_CODE_LENGTH)
	private String inviteCode;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private Team(String name, String description, String inviteCode, Instant now) {
		this.name = name;
		this.description = description;
		this.inviteCode = inviteCode;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * 규칙에 맞지 않으면 InvalidTeamNameException, InvalidTeamDescriptionException.
	 * 이름은 앞뒤 공백을 빼고, 소개 문구는 그대로 NFC로만 합쳐 저장한다. 초대 코드를 새로 만든다.
	 */
	public static Team createTeam(String name, String description, Instant now) {
		if (!isValidName(name)) {
			throw new InvalidTeamNameException();
		}
		if (!isValidDescription(description)) {
			throw new InvalidTeamDescriptionException();
		}
		return new Team(normalizeName(name), normalizeDescription(description), generateInviteCode(), now);
	}

	/** 새 코드로 바꾼다. 이전 코드는 어디에도 남지 않아 바로 무효가 된다. */
	public void regenerateInviteCode(Instant now) {
		this.inviteCode = generateInviteCode();
		this.updatedAt = now;
	}

	/** NFC로 합치고 앞뒤 공백을 뺀 값. null은 null. */
	private static String normalizeName(String name) {
		return name == null ? null : Normalizer.normalize(name, Normalizer.Form.NFC).strip();
	}

	/** 정규화한 값이 규칙에 맞는지. 남은 글자는 모두 BMP 문자라 정규식의 글자 수가 코드 포인트 수와 같다. */
	private static boolean isValidName(String name) {
		return name != null && NAME_PATTERN.matcher(normalizeName(name)).matches();
	}

	/** NFC로 합친 값. 공백은 그대로 둔다. null과 빈 문자열은 소개 없음(null). */
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

	private static String generateInviteCode() {
		byte[] bytes = new byte[INVITE_CODE_RANDOM_BYTES];
		RANDOM.nextBytes(bytes);
		return INVITE_CODE_ENCODER.encodeToString(bytes);
	}

}
