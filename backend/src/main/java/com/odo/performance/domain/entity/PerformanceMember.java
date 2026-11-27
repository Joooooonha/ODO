package com.odo.performance.domain.entity;

import java.time.Instant;
import java.util.Comparator;

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

/**
 * 공연 명단의 한 사람. 한 사람은 한 공연에 한 번만 들어간다(unique).
 * 명단의 팀은 공연의 팀과 같고, 그 팀의 팀원만 들어간다. 둘 다 DB의 복합 FK가 보장한다(V3 마이그레이션).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "performance_member")
public class PerformanceMember {

	/** 명단 순서. 디렉터를 먼저, 같은 역할 안에서는 명단에 들어온 순서. 한 요청으로 같이 들어온 사람은 요청 순서(id). */
	public static final Comparator<PerformanceMember> ROSTER_ORDER = Comparator
		.comparing((PerformanceMember member) -> !member.isDirector())
		.thenComparing(PerformanceMember::getJoinedAt)
		.thenComparing(PerformanceMember::getId);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "performance_id", nullable = false)
	private Long performanceId;

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PerformanceRole role;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private PerformanceMember(long performanceId, long teamId, long accountId, PerformanceRole role, Instant now) {
		this.performanceId = performanceId;
		this.teamId = teamId;
		this.accountId = accountId;
		this.role = role;
		this.joinedAt = now;
		this.updatedAt = now;
	}

	public static PerformanceMember createPerformanceMember(long performanceId, long teamId, long accountId,
			PerformanceRole role, Instant now) {
		return new PerformanceMember(performanceId, teamId, accountId, role, now);
	}

	public boolean isDirector() {
		return role == PerformanceRole.DIRECTOR;
	}

	/** 디렉터로 바꾸고 바뀌었는지를 돌려준다. 이미 디렉터면 아무것도 바꾸지 않고 false다. */
	public boolean designateDirector(Instant now) {
		if (isDirector()) {
			return false;
		}
		this.role = PerformanceRole.DIRECTOR;
		this.updatedAt = now;
		return true;
	}

}
