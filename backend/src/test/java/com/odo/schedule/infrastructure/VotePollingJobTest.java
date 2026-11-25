package com.odo.schedule.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.odo.schedule.application.VoteProcessor;
import com.odo.schedule.application.VoteProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 투표 작업자의 다음 실행 시각. 03_Schedule_spec.md의 "다음 처리 시각 (통합과 단위)".
 * 실행 시각은 스케줄러의 지금에 작업자가 정한 기다릴 시간을 더해 만든다.
 */
class VotePollingJobTest {

	private static final Instant SCHEDULER_NOW = Instant.parse("2026-11-25T10:00:00Z");

	private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

	private final VoteProcessor processor = mock(VoteProcessor.class);

	private final VotePollingJob job = new VotePollingJob(processor,
			new VoteProperties(Duration.ofSeconds(4), POLL_INTERVAL, true));

	private final SimpleTriggerContext context = new SimpleTriggerContext(
			Clock.fixed(SCHEDULER_NOW, ZoneOffset.UTC));

	@Test
	@DisplayName("다음 실행 시각은 스케줄러의 지금 + nextRunDelay()다")
	void schedulerNowPlusDelay() {
		when(processor.nextRunDelay()).thenReturn(Duration.ofMillis(10));

		assertThat(job.nextExecution(context)).isEqualTo(SCHEDULER_NOW.plusMillis(10));
	}

	@Test
	@DisplayName("nextRunDelay()가 예외를 던지면 스케줄러의 지금 + poll-interval이다. 작업자가 멈추지 않는다")
	void fallsBackToPollInterval() {
		when(processor.nextRunDelay()).thenThrow(new IllegalStateException("DB 오류"));

		assertThat(job.nextExecution(context)).isEqualTo(SCHEDULER_NOW.plus(POLL_INTERVAL));
	}

	@Test
	@DisplayName("한 번 돌면 currentCutoff()의 기준으로 처리하고, 다음 실행 시각은 같은 기준의 nextRunDelay(cutoff)로 정한다")
	void passesCutoffOfLastRun() {
		Instant cutoff = Instant.parse("2026-11-25T09:59:56Z");
		when(processor.currentCutoff()).thenReturn(cutoff);
		when(processor.nextRunDelay(cutoff)).thenReturn(Duration.ZERO);

		job.run();

		verify(processor).processDue(cutoff);
		assertThat(job.nextExecution(context)).isEqualTo(SCHEDULER_NOW);
	}

}
