package com.odo.schedule.domain.entity;

import java.time.Instant;

import com.odo.common.crypto.EncryptedStringConverter;
import com.odo.common.text.UserTextRule;
import com.odo.schedule.domain.exception.InvalidPracticeRoomAddressException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomMemoException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomNameException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomPasswordException;
import com.odo.schedule.domain.exception.InvalidRentalFeeException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 팀의 연습실 목록에 저장한 연습실. 일정이 참조한다. 03_Schedule_spec.md의 "장소와 연습실 목록".
 * 이름만 필수다. 비밀번호는 DB에 암호문으로 저장한다(EncryptedStringConverter). 엔티티 값은 평문이다.
 * 삭제는 삭제 표시(deletedAt)이고 조건부 UPDATE(PracticeRoomRepository.markDeleted)로 한다.
 * 비밀번호가 로그에 남지 않도록 toString을 만들지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "practice_room")
public class PracticeRoom {

	private static final int NAME_MAX_LENGTH = 50;

	private static final int ADDRESS_MAX_LENGTH = 50;

	private static final int PASSWORD_MAX_LENGTH = 50;

	private static final int MEMO_MAX_LENGTH = 300;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Column(nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Column(length = ADDRESS_MAX_LENGTH)
	private String address;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "password_encrypted")
	private String password;

	@Column(name = "rental_fee")
	private Integer rentalFee;

	@Column(length = MEMO_MAX_LENGTH)
	private String memo;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** 항목이 규칙에 맞지 않으면 그 항목의 예외. */
	public static PracticeRoom createRoom(long teamId, String name, String address, String password, Long rentalFee,
			String memo, Instant now) {
		PracticeRoom room = new PracticeRoom();
		room.teamId = teamId;
		room.createdAt = now;
		room.apply(name, address, password, rentalFee, memo, now);
		return room;
	}

	/** 보낸 값으로 모두 바꾼다. 보내지 않은 선택 항목은 비워진다. */
	public void update(String name, String address, String password, Long rentalFee, String memo, Instant now) {
		apply(name, address, password, rentalFee, memo, now);
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	private void apply(String name, String address, String password, Long rentalFee, String memo, Instant now) {
		this.name = requireValidName(name);
		this.address = requireValidAddress(address);
		this.password = requireValidPassword(password);
		this.rentalFee = requireValidRentalFee(rentalFee);
		this.memo = requireValidMemo(memo);
		this.updatedAt = now;
	}

	/** 앞뒤 공백을 뺀 1~50자. 아니면 InvalidPracticeRoomNameException. */
	public static String requireValidName(String name) {
		String normalized = UserTextRule.normalize(name);
		if (normalized == null || normalized.isEmpty() || !fitsSingleLine(normalized, NAME_MAX_LENGTH)) {
			throw new InvalidPracticeRoomNameException();
		}
		return normalized;
	}

	/** 비어 있으면 null. 50자 이하 한 줄. 아니면 InvalidPracticeRoomAddressException. */
	public static String requireValidAddress(String address) {
		String normalized = emptyToNull(UserTextRule.normalize(address));
		if (normalized != null && !fitsSingleLine(normalized, ADDRESS_MAX_LENGTH)) {
			throw new InvalidPracticeRoomAddressException();
		}
		return normalized;
	}

	/** 비어 있으면 null. 50자 이하 한 줄. 아니면 InvalidPracticeRoomPasswordException. */
	public static String requireValidPassword(String password) {
		String normalized = emptyToNull(UserTextRule.normalize(password));
		if (normalized != null && !fitsSingleLine(normalized, PASSWORD_MAX_LENGTH)) {
			throw new InvalidPracticeRoomPasswordException();
		}
		return normalized;
	}

	/** 비어 있으면 null. 300자 이하이고 줄바꿈을 쓸 수 있다. 아니면 InvalidPracticeRoomMemoException. */
	public static String requireValidMemo(String memo) {
		String normalized = emptyToNull(UserTextRule.normalize(memo));
		if (normalized != null && (codePoints(normalized) > MEMO_MAX_LENGTH
				|| UserTextRule.containsForbiddenCharForMultiLine(normalized))) {
			throw new InvalidPracticeRoomMemoException();
		}
		return normalized;
	}

	/**
	 * 없으면 null. 0 이상이고 저장 타입(integer)의 상한 이하. 아니면 InvalidRentalFeeException.
	 * 상한을 넘는 값도 이 오류로 알리려고 요청에서는 long으로 받는다.
	 */
	public static Integer requireValidRentalFee(Long rentalFee) {
		if (rentalFee == null) {
			return null;
		}
		if (rentalFee < 0 || rentalFee > Integer.MAX_VALUE) {
			throw new InvalidRentalFeeException();
		}
		return rentalFee.intValue();
	}

	private static boolean fitsSingleLine(String normalized, int maxLength) {
		return codePoints(normalized) <= maxLength && !UserTextRule.containsForbiddenCharForSingleLine(normalized);
	}

	/** PostgreSQL의 varchar 길이와 같은 단위(코드 포인트)로 센다. */
	private static int codePoints(String text) {
		return text.codePointCount(0, text.length());
	}

	private static String emptyToNull(String normalized) {
		return normalized == null || normalized.isEmpty() ? null : normalized;
	}

}
