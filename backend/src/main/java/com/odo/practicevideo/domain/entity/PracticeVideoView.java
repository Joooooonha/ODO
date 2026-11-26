package com.odo.practicevideo.domain.entity;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시청 기록. 대상자가 영상 화면에 처음 들어온 시각이다. 08_PracticeVideo_spec.md의 "대상자와 시청 확인".
 * 기록은 PracticeVideoViewRepository.record(INSERT ... ON CONFLICT DO NOTHING)로만 하고, 엔티티는 읽기에 쓴다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(PracticeVideoView.Key.class)
@Table(name = "practice_video_view")
public class PracticeVideoView {

	@Id
	@Column(name = "video_id")
	private Long videoId;

	@Id
	@Column(name = "account_id")
	private Long accountId;

	@Column(name = "viewed_at", nullable = false)
	private Instant viewedAt;

	/** 기본 키 (video_id, account_id). */
	public record Key(Long videoId, Long accountId) implements Serializable {
	}

}
