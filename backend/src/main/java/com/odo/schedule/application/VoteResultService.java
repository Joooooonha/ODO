package com.odo.schedule.application;

import com.odo.schedule.application.dto.VoteRequestResult;
import com.odo.schedule.domain.exception.VoteRequestNotFoundException;
import com.odo.schedule.domain.repository.VoteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 투표 결과 조회. 03_Schedule_spec.md의 "투표 결과".
 * 요청한 본인만 본다. 다른 사람의 요청이면 없는 것과 같이 VoteRequestNotFoundException이다(요청이 있다는 것도 드러내지 않는다).
 */
@Service
@RequiredArgsConstructor
public class VoteResultService {

	private final VoteRequestRepository voteRequestRepository;

	@Transactional(readOnly = true)
	public VoteRequestResult findResult(long accountId, long requestId) {
		return voteRequestRepository.findById(requestId)
			.filter(request -> request.getAccountId() == accountId)
			.map(VoteRequestResult::from)
			.orElseThrow(VoteRequestNotFoundException::new);
	}

}
