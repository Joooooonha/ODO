package com.odo.team.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 팀원. 한 사람은 한 팀에 한 번만 들어가고(unique), 팀마다 팀장은 한 명이다(부분 유니크 인덱스).
 * 팀과 계정은 ID로만 참조한다. 다른 기능도 team_id와 account_id로 팀원을 가리킨다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "team_member")
public class TeamMember {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private TeamRole role;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	private Integer generation;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private TeamMember(long teamId, long accountId, TeamRole role, Integer generation, Instant now) {
		Generation.validate(generation);
		this.teamId = teamId;
		this.accountId = accountId;
		this.role = role;
		this.generation = generation;
		this.joinedAt = now;
		this.updatedAt = now;
	}

	/** 팀을 만든 사람. 기수가 규칙에 맞지 않으면 InvalidGenerationException. */
	public static TeamMember leader(long teamId, long accountId, Integer generation, Instant now) {
		return new TeamMember(teamId, accountId, TeamRole.LEADER, generation, now);
	}

	/** 입장 요청이 승인된 사람. 기수가 규칙에 맞지 않으면 InvalidGenerationException. */
	public static TeamMember member(long teamId, long accountId, Integer generation, Instant now) {
		return new TeamMember(teamId, accountId, TeamRole.MEMBER, generation, now);
	}

	public boolean isLeader() {
		return role == TeamRole.LEADER;
	}

	/** null이면 기수를 비운다. 규칙에 맞지 않으면 InvalidGenerationException. */
	public void changeGeneration(Integer generation, Instant now) {
		Generation.validate(generation);
		this.generation = generation;
		this.updatedAt = now;
	}

}
