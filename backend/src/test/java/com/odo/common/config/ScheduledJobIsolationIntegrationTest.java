package com.odo.common.config;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.odo.common.time.SntpTimeSource;
import com.odo.schedule.application.VoteProcessor;
import com.odo.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 스케줄 작업이 서로를 막지 않는다. 03_Schedule_spec.md의 "스케줄 작업 격리 (통합)", 설계의 "작업자 운영".
 * 작업자를 켜고, 시계 감시의 측정이 테스트가 풀어 줄 때까지 멈춰 있게 한다(기준 서버가 응답하지 않는 경우).
 * 그동안에도 투표 작업자가 계속 돌아야 한다.
 */
@IntegrationTest
@Import(ScheduledJobIsolationIntegrationTest.BlockingTimeSourceConfig.class)
@TestPropertySource(properties = { "odo.vote.worker-enabled=true", "odo.clock.reference-host=time.example.test",
		"odo.clock.check-interval=100ms" })
class ScheduledJobIsolationIntegrationTest {

	@MockitoSpyBean
	VoteProcessor voteProcessor;

	@Autowired
	BlockingTimeSource timeSource;

	@Test
	@DisplayName("시계 감시가 기준 서버의 응답을 기다리는 동안에도 투표 작업자가 돈다")
	void workerRunsWhileClockCheckBlocked() throws Exception {
		assertThat(timeSource.entered.await(5, TimeUnit.SECONDS)).as("시계 감시가 측정을 시작했다").isTrue();
		try {
			clearInvocations(voteProcessor);
			// 폴링 주기는 200ms다. 측정이 멈춘 동안 3초 안에 세 번 넘게 불려야 한다.
			verify(voteProcessor, timeout(3_000).atLeast(3)).processDue();
		}
		finally {
			timeSource.release.countDown();
		}
	}

	/** 처음 잴 때 테스트가 풀어 줄 때까지 멈춘다. 풀린 뒤에는 바로 차이 0을 돌려준다. */
	static final class BlockingTimeSource implements SntpTimeSource {

		final CountDownLatch entered = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);

		@Override
		public Duration measureOffset(String host) {
			entered.countDown();
			try {
				// 테스트가 실패해도 스레드가 영원히 멈추지 않게 상한을 둔다.
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return Duration.ZERO;
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class BlockingTimeSourceConfig {

		@Bean
		@Primary
		BlockingTimeSource blockingTimeSource() {
			return new BlockingTimeSource();
		}

	}

}
