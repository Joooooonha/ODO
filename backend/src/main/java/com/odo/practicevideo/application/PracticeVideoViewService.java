package com.odo.practicevideo.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.odo.account.application.AccountReader;
import com.odo.performance.domain.entity.PerformanceMember;
import com.odo.performance.domain.repository.PerformanceMemberRepository;
import com.odo.practicevideo.application.dto.PracticeVideoViewsResult;
import com.odo.practicevideo.domain.entity.PracticeVideo;
import com.odo.practicevideo.domain.entity.PracticeVideoView;
import com.odo.practicevideo.domain.repository.PracticeVideoViewRepository;
import com.odo.team.application.TeamMemberReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시청 확인 기록과 시청 현황. 08_PracticeVideo_spec.md의 "대상자와 시청 확인", API 명세의 "시청 확인 기록", "시청 현황".
 * 대상자는 저장하지 않고 볼 때마다 "지금의 명단에서 올린 디렉터를 뺀 사람"으로 계산한다.
 */
@Service
@RequiredArgsConstructor
public class PracticeVideoViewService {

	private final PracticeVideoAccess practiceVideoAccess;

	private final PracticeVideoViewRepository viewRepository;

	private final PerformanceMemberRepository performanceMemberRepository;

	private final TeamMemberReader teamMemberReader;

	private final AccountReader accountReader;

	private final Clock clock;

	/**
	 * 대상자가 영상 화면에 들어온 것을 한 번 기록한다. 이미 있으면 처음 시각을 둔다. 대상자가 아니면(올린 디렉터) 기록하지 않는다.
	 * 볼 수 없음 PracticeVideoNotFoundException, 내가 올리는 중 PracticeVideoNotReadyException.
	 */
	@Transactional
	public void recordView(long accountId, long videoId) {
		PracticeVideo video = practiceVideoAccess.requireReadyVideo(videoId, accountId);
		if (video.isUploadedBy(accountId)) {
			return;
		}
		viewRepository.record(videoId, accountId, clock.instant());
	}

	/**
	 * 올린 디렉터만 본다. 본 사람은 먼저 본 순서, 안 본 사람은 명단 순서다. 명단에서 빠진 사람의 기록은 나오지 않는다.
	 * 볼 수 없음 PracticeVideoNotFoundException, 올린 사람 아님 NotPracticeVideoUploaderException,
	 * 업로드 중 PracticeVideoNotReadyException.
	 */
	@Transactional(readOnly = true)
	public PracticeVideoViewsResult findViews(long accountId, long videoId) {
		PracticeVideo video = practiceVideoAccess.requireVisibleVideo(videoId, accountId);
		practiceVideoAccess.requireUploader(video, accountId);
		practiceVideoAccess.requireReady(video);

		List<PerformanceMember> targets = performanceMemberRepository.findAllByPerformanceId(video.getPerformanceId())
			.stream()
			.filter(member -> member.getAccountId() != video.getUploaderAccountId())
			.sorted(PerformanceMember.ROSTER_ORDER)
			.toList();
		Map<Long, PerformanceMember> targetsById = targets.stream()
			.collect(Collectors.toMap(PerformanceMember::getAccountId, Function.identity()));
		Map<Long, String> names = accountReader.namesOf(targetsById.keySet());
		Map<Long, Integer> generations = targets.isEmpty() ? Map.of()
				: teamMemberReader.generationsOf(targets.get(0).getTeamId());

		List<PracticeVideoView> views = viewRepository.findAllByVideoIdOrderByViewedAtAscAccountIdAsc(videoId)
			.stream()
			.filter(view -> targetsById.containsKey(view.getAccountId()))
			.toList();
		List<PracticeVideoViewsResult.Viewed> viewed = views.stream()
			.map(view -> new PracticeVideoViewsResult.Viewed(view.getAccountId(), names.get(view.getAccountId()),
					generations.get(view.getAccountId()), view.getViewedAt()))
			.toList();
		var viewedIds = views.stream().map(PracticeVideoView::getAccountId).collect(Collectors.toSet());
		List<PracticeVideoViewsResult.NotViewed> notViewed = targets.stream()
			.filter(member -> !viewedIds.contains(member.getAccountId()))
			.map(member -> new PracticeVideoViewsResult.NotViewed(member.getAccountId(),
					names.get(member.getAccountId()), generations.get(member.getAccountId())))
			.toList();
		return new PracticeVideoViewsResult(targets.size(), viewed.size(), viewed, notViewed);
	}

}
