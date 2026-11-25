package com.odo.practicevideo.infrastructure;

import com.odo.practicevideo.application.PracticeVideoObjectRemover;
import com.odo.practicevideo.application.PracticeVideoProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * odo.practice-video.cleanup-interval(10분)마다 삭제한 영상과 완료 기한이 지난 업로드를 청소한다. 08_PracticeVideo_spec.md의 "청소 작업".
 * 각 서버가 따로 돌리고, 같은 영상을 두 서버가 함께 처리하지 않는 것은 PracticeVideoObjectRemover의 행 잠금(SKIP LOCKED)이 맡는다.
 * 테스트는 이 작업을 끄고(odo.practice-video.cleanup-enabled=false) removeExpired()를 직접 부른다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "odo.practice-video.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class PracticeVideoCleanupJob implements SchedulingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(PracticeVideoCleanupJob.class);

	private final PracticeVideoObjectRemover objectRemover;

	private final PracticeVideoProperties properties;

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addFixedDelayTask(this::run, properties.cleanupInterval());
	}

	void run() {
		int removed = objectRemover.removeExpired();
		if (removed > 0) {
			log.info("연습 영상 {}개를 청소했다", removed);
		}
	}

}
