package com.odo.account.domain.entity;

import java.time.Instant;

import com.odo.account.domain.exception.InvalidNameException;
import com.odo.account.domain.rule.NameRule;
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
 * 계정. 이름은 항상 이름 규칙을 지킨 값이다(가입할 때는 정리한 닉네임).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "account")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = NameRule.MAX_LENGTH)
	private String name;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private Account(String name, Instant now) {
		this.name = name;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** 카카오 닉네임을 정리해 이름으로 넣은 새 계정. */
	public static Account signUpWithNickname(String nickname, Instant now) {
		return new Account(NameRule.nameFromNickname(nickname), now);
	}

	/** 이름 규칙에 맞지 않으면 InvalidNameException. 저장하는 값은 NFC로 정규화한다. */
	public void changeName(String newName, Instant now) {
		if (!NameRule.isValid(newName)) {
			throw new InvalidNameException();
		}
		this.name = NameRule.normalize(newName);
		this.updatedAt = now;
	}

}
