package com.odo.schedule.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * odo.vote.* 설정. 03_Schedule_spec.md의 "설정".
 *
 * @param waitTime 도착 시각을 찍은 뒤 처리하기까지 기다리는 시간(W, 설정 키는 wait). 1차는 4초로 고정한다.
 *     record 항목 이름을 wait로 하면 Object.wait()와 겹쳐 쓸 수 없다
 * @param pollInterval 작업자가 처리할 요청이 있는지 확인하는 주기. 정확성과 관계없고, 결과가 최대 그만큼 늦어진다
 * @param workerEnabled 작업자를 돌릴지. 테스트는 끄고 VoteProcessor.processDue()를 직접 부른다
 */
@ConfigurationProperties("odo.vote")
public record VoteProperties(
		@Name("wait") Duration waitTime,
		Duration pollInterval,
		boolean workerEnabled) {

	public VoteProperties {
		if (waitTime == null || waitTime.isNegative() || waitTime.isZero()) {
			throw new IllegalStateException("odo.vote.wait는 0보다 커야 한다");
		}
		if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
			throw new IllegalStateException("odo.vote.poll-interval은 0보다 커야 한다");
		}
	}

}
