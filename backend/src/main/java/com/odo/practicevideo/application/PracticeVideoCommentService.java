package com.odo.practicevideo.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.odo.account.application.AccountReader;
import com.odo.common.error.InvalidRequestException;
import com.odo.practicevideo.application.dto.PracticeVideoCommentResult;
import com.odo.practicevideo.application.dto.PracticeVideoThreadResult;
import com.odo.practicevideo.domain.entity.PracticeVideoComment;
import com.odo.practicevideo.domain.entity.PracticeVideoThread;
import com.odo.practicevideo.domain.exception.PracticeVideoNotFoundException;
import com.odo.practicevideo.domain.exception.PracticeVideoNotReadyException;
import com.odo.practicevideo.domain.exception.PracticeVideoThreadNotFoundException;
import com.odo.practicevideo.domain.repository.PracticeVideoCommentRepository;
import com.odo.practicevideo.domain.repository.PracticeVideoThreadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시점 코멘트. 08_PracticeVideo_spec.md의 "시점 코멘트", API 명세의 "코멘트 목록", "스레드 만들기", "스레드에 답하기".
 * 명단의 누구나 보고 쓴다. 영상이 READY인지 먼저 확인한다. 확인한 직후 삭제되면 코멘트가 들어가지만, 영상 행을 지울 때
 * 함께 지워지므로 그대로 둔다.
 */
@Service
@RequiredArgsConstructor
public class PracticeVideoCommentService {

	private final PracticeVideoAccess practiceVideoAccess;

	private final PracticeVideoThreadRepository threadRepository;

	private final PracticeVideoCommentRepository commentRepository;

	private final AccountReader accountReader;

	private final Clock clock;

	/**
	 * 그 영상의 모든 스레드와 코멘트. 스레드는 시점 순, 같으면 만든 순서이고, 스레드 안은 단 순서다.
	 * 볼 수 없음 PracticeVideoNotFoundException, 내가 올리는 중 PracticeVideoNotReadyException.
	 */
	@Transactional(readOnly = true)
	public List<PracticeVideoThreadResult> findThreads(long accountId, long videoId) {
		practiceVideoAccess.requireReadyVideo(videoId, accountId);
		List<PracticeVideoThread> threads = threadRepository.findAllByVideoIdOrderByPositionSecondsAscIdAsc(videoId);
		List<PracticeVideoComment> comments = commentRepository.findAllByVideoIdOrderByThreadIdAscIdAsc(videoId);
		Set<Long> authorIds = new HashSet<>();
		comments.forEach(comment -> authorIds.add(comment.getAuthorAccountId()));
		Map<Long, String> names = accountReader.namesOf(authorIds);
		Map<Long, List<PracticeVideoCommentResult>> byThread = comments.stream()
			.collect(Collectors.groupingBy(PracticeVideoComment::getThreadId, Collectors.mapping(
					comment -> PracticeVideoCommentResult.of(comment, names.get(comment.getAuthorAccountId()),
							accountId),
					Collectors.toList())));
		return threads.stream()
			.map(thread -> PracticeVideoThreadResult.of(thread, byThread.getOrDefault(thread.getId(), List.of())))
			.toList();
	}

	/**
	 * 영상의 positionSeconds초에 새 스레드를 열고 첫 코멘트를 단다. 한 트랜잭션이다.
	 * 확인 순서는 API 명세의 오류 표 순서다. 볼 수 없음 PracticeVideoNotFoundException,
	 * 내가 올리는 중 PracticeVideoNotReadyException, 시점 없음 InvalidRequestException,
	 * 시점이 음수 InvalidCommentPositionException, 내용 규칙 위반 InvalidCommentContentException.
	 */
	@Transactional
	public PracticeVideoThreadResult createThread(long accountId, long videoId, Integer positionSeconds,
			String content) {
		practiceVideoAccess.requireReadyVideo(videoId, accountId);
		if (positionSeconds == null) {
			throw new InvalidRequestException();
		}
		PracticeVideoThread.requireValidPosition(positionSeconds);
		PracticeVideoComment.requireValidContent(content);
		Instant now = clock.instant();
		PracticeVideoThread thread = threadRepository
			.save(PracticeVideoThread.open(videoId, positionSeconds, accountId, now));
		PracticeVideoComment first = commentRepository.save(PracticeVideoComment.write(thread, accountId, content, now));
		return PracticeVideoThreadResult.of(thread, List.of(commentResult(first, accountId)));
	}

	/**
	 * 스레드에 코멘트를 단다. 시점은 스레드의 시점이다.
	 * 스레드가 없거나, 그 영상을 볼 수 없거나, 영상이 삭제 중 PracticeVideoThreadNotFoundException,
	 * 내용 규칙 위반 InvalidCommentContentException.
	 */
	@Transactional
	public PracticeVideoCommentResult reply(long accountId, long threadId, String content) {
		PracticeVideoThread thread = threadRepository.findById(threadId)
			.orElseThrow(PracticeVideoThreadNotFoundException::new);
		try {
			practiceVideoAccess.requireReadyVideo(thread.getVideoId(), accountId);
		}
		catch (PracticeVideoNotFoundException | PracticeVideoNotReadyException ex) {
			// URL이 가리키는 자원은 스레드다. 스레드는 READY인 영상에만 생기므로 업로드 중인 경우는 없다.
			throw new PracticeVideoThreadNotFoundException();
		}
		PracticeVideoComment comment = commentRepository
			.save(PracticeVideoComment.write(thread, accountId, content, clock.instant()));
		return commentResult(comment, accountId);
	}

	private PracticeVideoCommentResult commentResult(PracticeVideoComment comment, long accountId) {
		return PracticeVideoCommentResult.of(comment, accountReader.nameOf(comment.getAuthorAccountId()), accountId);
	}

}
