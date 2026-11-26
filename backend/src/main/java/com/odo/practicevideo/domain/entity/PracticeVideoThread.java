package com.odo.practicevideo.domain.entity;

import java.time.Instant;

import com.odo.practicevideo.domain.exception.InvalidCommentPositionException;
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
 * 시점 스레드. 영상의 한 시점(초)에 붙고, 코멘트가 그 안에 들어간다. 08_PracticeVideo_spec.md의 "시점 코멘트".
 * 영상에서 코멘트를 쓰면 새 스레드가 생기고, 그 코멘트가 첫 코멘트다. 같은 초에 여러 개 있을 수 있다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "practice_video_thread")
public class PracticeVideoThread {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "video_id", nullable = false)
	private Long videoId;

	@Column(name = "position_seconds", nullable = false)
	private Integer positionSeconds;

	@Column(name = "created_by_account_id", nullable = false)
	private Long createdByAccountId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	private PracticeVideoThread(long videoId, int positionSeconds, long createdByAccountId, Instant now) {
		this.videoId = videoId;
		this.positionSeconds = positionSeconds;
		this.createdByAccountId = createdByAccountId;
		this.createdAt = now;
	}

	/** 영상의 positionSeconds초에 스레드를 연다. 시점이 0보다 작으면 InvalidCommentPositionException. */
	public static PracticeVideoThread open(long videoId, int positionSeconds, long createdByAccountId, Instant now) {
		return new PracticeVideoThread(videoId, requireValidPosition(positionSeconds), createdByAccountId, now);
	}

	/** 0 이상인지만 본다. 서버는 영상 길이를 모르므로 상한은 없다. */
	public static int requireValidPosition(int positionSeconds) {
		if (positionSeconds < 0) {
			throw new InvalidCommentPositionException();
		}
		return positionSeconds;
	}

}
