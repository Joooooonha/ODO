package com.odo.practicevideo.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * odo.practice-video.* 설정. 08_PracticeVideo_spec.md의 "설정".
 *
 * @param uploadUrlTtl 업로드 주소의 만료. 이 안에 전송을 시작해야 한다
 * @param uploadDeadline 업로드 시작부터 완료까지의 기한. 지나면 실패로 보고 청소 작업이 지운다
 * @param playbackUrlTtl 재생 주소의 만료
 * @param pageSize 목록 한 번에 가져오는 개수
 * @param cleanupInterval 청소 작업의 주기
 * @param cleanupEnabled 청소 작업을 돌릴지. 테스트는 끄고 PracticeVideoObjectRemover.removeExpired()를 직접 부른다
 */
@ConfigurationProperties("odo.practice-video")
public record PracticeVideoProperties(
		Duration uploadUrlTtl,
		Duration uploadDeadline,
		Duration playbackUrlTtl,
		int pageSize,
		Duration cleanupInterval,
		boolean cleanupEnabled) {

	public PracticeVideoProperties {
		requirePositive("odo.practice-video.upload-url-ttl", uploadUrlTtl);
		requirePositive("odo.practice-video.upload-deadline", uploadDeadline);
		requirePositive("odo.practice-video.playback-url-ttl", playbackUrlTtl);
		requirePositive("odo.practice-video.cleanup-interval", cleanupInterval);
		if (pageSize < 1) {
			throw new IllegalStateException("odo.practice-video.page-size는 1 이상이어야 한다");
		}
		if (uploadDeadline.compareTo(uploadUrlTtl) < 0) {
			throw new IllegalStateException("odo.practice-video.upload-deadline은 upload-url-ttl보다 짧을 수 없다");
		}
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isNegative() || value.isZero()) {
			throw new IllegalStateException(name + "는 0보다 커야 한다");
		}
	}

}
