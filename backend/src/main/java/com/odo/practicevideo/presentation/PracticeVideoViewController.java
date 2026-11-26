package com.odo.practicevideo.presentation;

import com.odo.auth.presentation.LoginAccountId;
import com.odo.practicevideo.application.PracticeVideoPlayService;
import com.odo.practicevideo.application.PracticeVideoViewService;
import com.odo.practicevideo.presentation.dto.PracticeVideoViewsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 시청 확인 기록, 시청 현황, 재생 수. 08_PracticeVideo_spec.md의 API 명세. */
@RestController
@RequestMapping("/api/practice-videos/{id}")
@RequiredArgsConstructor
public class PracticeVideoViewController {

	private final PracticeVideoViewService viewService;

	private final PracticeVideoPlayService playService;

	/** "내 시청 기록"을 두는 요청이라 여러 번 보내도 결과가 같다(PUT). 본문 없이 204다. */
	@PutMapping("/views/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void recordView(@LoginAccountId long accountId, @PathVariable long id) {
		viewService.recordView(accountId, id);
	}

	@GetMapping("/views")
	public PracticeVideoViewsResponse findViews(@LoginAccountId long accountId, @PathVariable long id) {
		return PracticeVideoViewsResponse.from(viewService.findViews(accountId, id));
	}

	/** 부를 때마다 재생 수가 늘어 같은 요청의 결과가 다르다(POST). 바뀐 수는 돌려주지 않아 204다. */
	@PostMapping("/plays")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void play(@LoginAccountId long accountId, @PathVariable long id) {
		playService.play(accountId, id);
	}

}
