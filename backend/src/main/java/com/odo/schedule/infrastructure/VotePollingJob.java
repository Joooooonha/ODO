package com.odo.schedule.infrastructure;

import java.time.Duration;
import java.time.Instant;

import com.odo.schedule.application.VoteProcessor;
import com.odo.schedule.application.VoteProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * 투표 작업자를 돌린다. 한 번 돈 뒤 그 실행의 기준으로 구한 VoteProcessor.nextRunDelay(cutoff)만큼 기다렸다가 다시 돈다. 가장 이른 처리 시각에 깨어나고,
 * 늦어도 odo.vote.poll-interval마다 돈다. 03_Schedule_spec.md의 "작업자 운영".
 * 각 서버가 따로 돌리고, 같은 일정을 두 서버가 함께 처리하지 않는 것은 VoteProcessor의 일정 행 잠금(SKIP LOCKED)이 맡는다.
 * 테스트는 이 작업을 끄고(odo.vote.worker-enabled=false) processDue()를 직접 부른다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "odo.vote.worker-enabled", havingValue = "true", matchIfMissing = true)
public class VotePollingJob implements SchedulingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(VotePollingJob.class);

	private final VoteProcessor voteProcessor;

	private final VoteProperties properties;

	/** 직전 실행이 쓴 기준(그때의 지금 − W). 아직 한 번도 돌지 않았으면 null이다. 스케줄러 스레드끼리 보이게 volatile로 둔다. */
	private volatile Instant lastCutoff;

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addTriggerTask(this::run, this::nextExecution);
	}

	/** 기준을 정해 처리하고 그 기준을 기억한다. 처리하다 예외가 나도 다음 실행 시각은 이 기준으로 정한다. */
	void run() {
		Instant cutoff = voteProcessor.currentCutoff();
		lastCutoff = cutoff;
		voteProcessor.processDue(cutoff);
	}

	/**
	 * 다음 실행 시각. 스케줄러의 지금에 기다릴 시간을 더한다. 기다릴 시간은 직전 실행의 기준으로 구해서, 앞의 요청을 처리하는 사이에
	 * 때가 된 요청이 있으면 바로 다시 돈다. 작업자는 주입된 Clock을, 스케줄러는 시스템 시계를 쓰므로
	 * 절대 시각을 넘기지 않고 기다릴 시간만 받는다. 구하다 예외가 나면 poll-interval 뒤에 다시 돈다.
	 * 여기서 예외가 밖으로 나가면 스케줄러가 이 작업을 다시 예약하지 않아 작업자가 멈춘다.
	 */
	Instant nextExecution(TriggerContext context) {
		Instant now = context.getClock().instant();
		Duration delay;
		try {
			Instant cutoff = lastCutoff;
			delay = cutoff == null ? voteProcessor.nextRunDelay() : voteProcessor.nextRunDelay(cutoff);
		}
		catch (RuntimeException ex) {
			log.error("투표 작업자의 다음 실행 시각을 구하지 못했다. poll-interval 뒤에 다시 돈다", ex);
			delay = properties.pollInterval();
		}
		return now.plus(delay);
	}

}
