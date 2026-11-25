package com.odo.common.time;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 파드의 시계가 기준 시간 서버와 얼마나 다른지 재서 로그로 남긴다. 03_Schedule_spec.md의 "시계 감시 (단위와 검증)".
 * 실제 SNTP 통신 대신 가짜 SntpTimeSource를 쓴다. 실제 통신은 배포 뒤 로그로 확인한다.
 */
class ClockOffsetMonitorTest {

	private static final String HOST = "time.example.test";

	private static final Duration INTERVAL = Duration.ofSeconds(30);

	private final Logger logger = (Logger) LoggerFactory.getLogger(ClockOffsetMonitor.class);

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	private Level originalLevel;

	@BeforeEach
	void captureLogs() {
		originalLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void releaseLogs() {
		logger.detachAppender(logs);
		logger.setLevel(originalLevel);
	}

	private List<ILoggingEvent> eventsAt(Level level) {
		return logs.list.stream().filter(event -> event.getLevel() == level).toList();
	}

	/** 늘 같은 차이를 돌려주는 가짜 시간 소스. 불린 횟수를 센다. */
	private static final class FixedSource implements SntpTimeSource {

		private final Duration offset;

		private final AtomicInteger calls = new AtomicInteger();

		private FixedSource(Duration offset) {
			this.offset = offset;
		}

		@Override
		public Duration measureOffset(String host) {
			calls.incrementAndGet();
			return offset;
		}

	}

	private static ClockOffsetMonitor monitor(SntpTimeSource source, String host) {
		return new ClockOffsetMonitor(source, new ClockProperties(host, INTERVAL));
	}

	@Test
	@DisplayName("차이가 1ms 이하면 DEBUG로만 남기고 WARN은 없다")
	void withinLimit() {
		FixedSource source = new FixedSource(Duration.ofNanos(400_000));

		monitor(source, HOST).check();

		assertThat(source.calls).hasValue(1);
		assertThat(eventsAt(Level.DEBUG)).hasSize(1);
		assertThat(eventsAt(Level.WARN)).isEmpty();
	}

	@Test
	@DisplayName("차이가 정확히 1ms면 WARN이 없다. 1ms를 넘을 때만 WARN이다")
	void exactlyLimit() {
		monitor(new FixedSource(Duration.ofMillis(1)), HOST).check();
		monitor(new FixedSource(Duration.ofMillis(-1)), HOST).check();

		assertThat(eventsAt(Level.WARN)).isEmpty();
	}

	@Test
	@DisplayName("차이가 1ms를 넘으면 앞서든 뒤처지든 WARN을 남기고, 메시지에 기준 서버가 들어간다")
	void overLimit() {
		monitor(new FixedSource(Duration.ofNanos(1_000_001)), HOST).check();
		monitor(new FixedSource(Duration.ofMillis(-3)), HOST).check();

		List<ILoggingEvent> warnings = eventsAt(Level.WARN);
		assertThat(warnings).hasSize(2);
		assertThat(warnings).allSatisfy(event -> assertThat(event.getFormattedMessage()).contains(HOST));
	}

	@Test
	@DisplayName("측정에 실패해도 예외가 밖으로 나가지 않고, 다음 측정은 그대로 한다")
	void measurementFailure() {
		AtomicInteger calls = new AtomicInteger();
		SntpTimeSource failing = host -> {
			if (calls.incrementAndGet() == 1) {
				throw new IOException("응답 없음");
			}
			return Duration.ZERO;
		};
		ClockOffsetMonitor monitor = monitor(failing, HOST);

		assertThatCode(monitor::check).doesNotThrowAnyException();
		assertThatCode(monitor::check).doesNotThrowAnyException();

		assertThat(calls).hasValue(2);
	}

	@Test
	@DisplayName("측정 중 런타임 예외가 나도 밖으로 나가지 않는다")
	void unexpectedFailure() {
		SntpTimeSource broken = host -> {
			throw new IllegalStateException("예상하지 못한 오류");
		};

		assertThatCode(monitor(broken, HOST)::check).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("기준 서버가 비어 있으면 처음에 WARN을 한 번 남기고 감시를 끈다. 몇 번 불러도 측정하지 않는다")
	void disabledWithoutHost() {
		FixedSource source = new FixedSource(Duration.ofMillis(5));

		ClockOffsetMonitor monitor = monitor(source, " ");
		monitor.check();
		monitor.check();

		assertThat(source.calls).hasValue(0);
		assertThat(eventsAt(Level.WARN)).hasSize(1);
	}

	@Test
	@DisplayName("기준 서버 설정이 없어도(null) 감시를 끄고 서버는 뜬다")
	void disabledWithNullHost() {
		FixedSource source = new FixedSource(Duration.ZERO);

		assertThatCode(() -> monitor(source, null).check()).doesNotThrowAnyException();

		assertThat(source.calls).hasValue(0);
	}

}
