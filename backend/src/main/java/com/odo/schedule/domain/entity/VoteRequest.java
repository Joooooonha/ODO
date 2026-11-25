package com.odo.schedule.domain.entity;

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

/**
 * 참여 투표 요청 한 건. 03_Schedule_spec.md의 "선착순 투표의 순서와 동시성", "투표 처리 세부".
 * 접수할 때는 도착 시각과 함께 PENDING으로 저장만 한다. 팀원인지, 마감, 정원은 작업자가 꺼낼 때 확인하고 결과를 적는다.
 * 일정이 지워져도 남는다(schedule_id에 FK가 없다). 그래야 결과 조회에서 "일정 삭제됨"을 답할 수 있다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "vote_request")
public class VoteRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "schedule_id", nullable = false)
	private Long scheduleId;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 3)
	private Vote choice;

	/** 서버 도착 시각(µs). 순서와 마감 판정의 기준이다. */
	@Column(name = "arrived_at", nullable = false)
	private Instant arrivedAt;

	/** 도착 시각을 찍은 서버의 호스트 이름. 시계가 틀린 서버 때문에 영향받은 요청을 찾을 때 쓴다. */
	@Column(name = "received_by", nullable = false, length = 63)
	private String receivedBy;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private VoteRequestStatus status;

	@Enumerated(EnumType.STRING)
	@Column(length = 30)
	private VoteInvalidReason reason;

	/** 이미 처리한 요청보다 이른 도착 시각으로 늦게 처리되었는지(역전). 결과는 그대로 둔다. */
	@Column(nullable = false)
	private boolean reordered;

	/** 요청 테이블에 저장된 시각. 도착 시각과의 차이가 W를 정하는 지연이다. */
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	public static VoteRequest accept(long scheduleId, long accountId, Vote choice, Instant arrivedAt,
			String receivedBy, Instant now) {
		VoteRequest request = new VoteRequest();
		request.scheduleId = scheduleId;
		request.accountId = accountId;
		request.choice = choice;
		request.arrivedAt = arrivedAt;
		request.receivedBy = receivedBy;
		request.status = VoteRequestStatus.PENDING;
		request.createdAt = now;
		return request;
	}

	public void confirm(boolean reordered, Instant now) {
		complete(VoteRequestStatus.CONFIRMED, null, reordered, now);
	}

	public void full(boolean reordered, Instant now) {
		complete(VoteRequestStatus.FULL, null, reordered, now);
	}

	public void invalidate(VoteInvalidReason reason, boolean reordered, Instant now) {
		complete(VoteRequestStatus.INVALID, reason, reordered, now);
	}

	private void complete(VoteRequestStatus status, VoteInvalidReason reason, boolean reordered, Instant now) {
		if (this.status != VoteRequestStatus.PENDING) {
			throw new IllegalStateException("이미 처리한 투표 요청이다: " + id);
		}
		this.status = status;
		this.reason = reason;
		this.reordered = reordered;
		this.processedAt = now;
	}

}
