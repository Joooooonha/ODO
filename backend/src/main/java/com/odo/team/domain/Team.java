package com.odo.team.domain;

import java.time.Instant;

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
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "team")
public class Team {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = TeamNameRule.MAX_LENGTH)
	private String name;

	@Column(length = TeamDescriptionRule.MAX_LENGTH)
	private String description;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "invite_code", nullable = false, length = InviteCode.LENGTH)
	private String inviteCode;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private Team(String name, String description, Instant now) {
		this.name = name;
		this.description = description;
		this.inviteCode = InviteCode.generate();
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	 * 규칙에 맞지 않으면 InvalidTeamNameException, InvalidTeamDescriptionException.
	 * 이름은 앞뒤 공백을 빼고, 소개 문구는 그대로 NFC로만 합쳐 저장한다. 초대 코드를 새로 만든다.
	 */
	public static Team create(String name, String description, Instant now) {
		if (!TeamNameRule.isValid(name)) {
			throw new InvalidTeamNameException();
		}
		if (!TeamDescriptionRule.isValid(description)) {
			throw new InvalidTeamDescriptionException();
		}
		return new Team(TeamNameRule.normalize(name), TeamDescriptionRule.normalize(description), now);
	}

	/** 새 코드로 바꾼다. 이전 코드는 어디에도 남지 않아 바로 무효가 된다. */
	public void regenerateInviteCode(Instant now) {
		this.inviteCode = InviteCode.generate();
		this.updatedAt = now;
	}

}
