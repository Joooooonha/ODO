package com.odo.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트에서 시간을 멈추거나 앞으로 보낼 수 있는 Clock.
 * 10초 유예, 30분 접근 토큰, 30일 갱신 토큰 같은 시간 조건을 기다리지 않고 만든다.
 */
public class MutableClock extends Clock {

	public static final Instant DEFAULT_START = Instant.parse("2026-11-20T09:00:00Z");

	private final AtomicReference<Instant> now = new AtomicReference<>(DEFAULT_START);

	public void set(Instant instant) {
		now.set(instant);
	}

	public void advance(Duration duration) {
		now.updateAndGet(current -> current.plus(duration));
	}

	public void reset() {
		now.set(DEFAULT_START);
	}

	@Override
	public Instant instant() {
		return now.get();
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

}
