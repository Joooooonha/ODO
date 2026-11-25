package com.odo.schedule.infrastructure;

import com.odo.schedule.application.VoteProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * odo.vote.poll-interval마다 투표 작업자를 돌린다. 각 서버가 따로 돌리고, 같은 일정을 두 서버가 함께 처리하지 않는 것은
 * VoteProcessor의 일정 행 잠금(SKIP LOCKED)이 맡는다. 테스트는 이 작업을 끄고(odo.vote.worker-enabled=false)
 * processDue()를 직접 부른다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "odo.vote.worker-enabled", havingValue = "true", matchIfMissing = true)
public class VotePollingJob {

	private final VoteProcessor voteProcessor;

	@Scheduled(fixedDelayString = "${odo.vote.poll-interval}")
	public void poll() {
		voteProcessor.processDue();
	}

}
