package com.odo.common.time;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이 파드의 시계가 기준 시간 서버와 얼마나 다른지 주기적으로 재서 로그로 남긴다. 03_Schedule_spec.md의 "서버 시계".
 * 시계를 맞추는 것은 노드의 chrony다. 이 감시는 그 결과를 확인만 하고, 요청 처리나 도착 시각에는 관여하지 않는다.
 * 1차는 로그로 남긴다. 잴 때마다 DEBUG, 차이가 1ms를 넘으면 WARN이다. 지표로 내보내는 것은 관측 세션의 미정 사항이다.
 * 기준 서버가 비어 있으면 뜰 때 WARN을 한 번 남기고 감시를 끈다. 순서의 정확성에 필수가 아니어서 서버가 뜨지 않게 하지는 않는다.
 */
@Component
public class ClockOffsetMonitor {

	private static final Logger log = LoggerFactory.getLogger(ClockOffsetMonitor.class);

	private static final Duration WARN_THRESHOLD = Duration.ofMillis(1);

	private final SntpTimeSource timeSource;

	private final String referenceHost;

	public ClockOffsetMonitor(SntpTimeSource timeSource, ClockProperties properties) {
		this.timeSource = timeSource;
		this.referenceHost = properties.referenceHost() == null || properties.referenceHost().isBlank() ? null
				: properties.referenceHost().strip();
		if (referenceHost == null) {
			log.warn("odo.clock.reference-host가 비어 있어 서버 시계 감시를 끈다");
		}
	}

	/** 한 번 잰다. 측정에 실패해도 예외를 밖으로 내보내지 않는다. 다음 주기에 다시 잰다. */
	@Scheduled(fixedDelayString = "${odo.clock.check-interval:30s}")
	public void check() {
		if (referenceHost == null) {
			return;
		}
		try {
			Duration offset = timeSource.measureOffset(referenceHost);
			String millis = String.format("%.3f", offset.toNanos() / 1_000_000.0);
			if (offset.abs().compareTo(WARN_THRESHOLD) > 0) {
				log.warn("서버 시계가 기준과 1ms 넘게 다르다: referenceHost={}, offsetMs={}", referenceHost, millis);
			}
			else {
				log.debug("서버 시계 차이: referenceHost={}, offsetMs={}", referenceHost, millis);
			}
		}
		catch (Exception ex) {
			log.warn("서버 시계 차이를 재지 못했다: referenceHost={}", referenceHost, ex);
		}
	}

}
