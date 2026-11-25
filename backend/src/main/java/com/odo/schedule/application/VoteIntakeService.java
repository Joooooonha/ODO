package com.odo.schedule.application;

import java.time.Clock;
import java.time.Instant;

import com.odo.schedule.application.dto.VoteRequestResult;
import com.odo.schedule.domain.entity.Vote;
import com.odo.schedule.domain.entity.VoteRequest;
import com.odo.schedule.domain.repository.VoteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 투표 접수. 03_Schedule_spec.md의 "접수 (도착부터 202까지)".
 * 요청 테이블에 INSERT 한 번만 한다. 팀원인지, 선택 참여자인지, 마감과 정원은 작업자가 꺼낼 때 확인한다.
 * 확인을 위한 DB 읽기를 더하면 도착 시각을 찍은 뒤 저장하기까지의 대기가 늘어 W 오차 예산을 쓰기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class VoteIntakeService {

	private final VoteRequestRepository voteRequestRepository;

	private final Clock clock;

	/** arrivedAt은 VoteArrivalFilter가 찍은 서버 도착 시각, receivedBy는 그 서버의 호스트 이름이다. */
	@Transactional
	public VoteRequestResult accept(long scheduleId, long accountId, Vote choice, Instant arrivedAt,
			String receivedBy) {
		VoteRequest request = VoteRequest.accept(scheduleId, accountId, choice, arrivedAt, receivedBy,
				clock.instant());
		return VoteRequestResult.from(voteRequestRepository.save(request));
	}

}
