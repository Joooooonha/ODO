package com.odo.practicevideo.presentation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.practicevideo.application.PracticeVideoObjectRemover;
import com.odo.practicevideo.presentation.PracticeVideoRequests.Fixture;
import com.odo.support.DatabaseCleaner;
import com.odo.support.InMemoryObjectStorage;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.odo.practicevideo.presentation.PracticeVideoRequests.uploadBody;
import static com.odo.practicevideo.presentation.PracticeVideoRequests.uploadUrlOf;
import static com.odo.practicevideo.presentation.PracticeVideoRequests.videoIdOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 청소 작업. 08_PracticeVideo_spec.md의 "청소 작업 (통합)", 설계의 "청소 작업", 구현 방법의 "삭제와 청소".
 * 작업은 꺼 두고(odo.practice-video.cleanup-enabled=false) removeExpired()를 직접 부른다.
 */
@IntegrationTest
class PracticeVideoCleanupIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	InMemoryObjectStorage storage;

	@Autowired
	PracticeVideoObjectRemover remover;

	private PracticeVideoRequests api;

	private Fixture f;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		storage.reset();
		api = new PracticeVideoRequests(mockMvc, accessTokenIssuer, jdbc, storage);
		f = api.setUpFixture(clock);
	}

	/** 객체 삭제가 실패해 DELETING으로 남은 영상. */
	private long deleting(String title) throws Exception {
		long id = api.uploadReady(f.choreographer(), f.performanceId(), f.choreoSong(), title);
		storage.failDeleteOnce(api.objectKeyOf(id));
		api.delete(f.choreographer(), id).andExpect(status().isNoContent());
		assertThat(api.statusOf(id)).isEqualTo("DELETING");
		return id;
	}

	@Test
	@DisplayName("DELETING과 완료 기한이 지난 UPLOADING은 객체와 행이 지워지고, 기한이 남은 UPLOADING과 READY는 남는다")
	void removes() throws Exception {
		long expired = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "기한 지남");
		String expiredKey = api.objectKeyOf(expired);
		clock.advance(Duration.ofHours(5));
		long deleting = deleting("지움");
		String deletingKey = api.objectKeyOf(deleting);
		long ready = api.uploadReady(f.choreographer(), f.performanceId(), f.choreoSong(), "완료");
		long uploading = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중");
		clock.advance(Duration.ofHours(1).plusSeconds(1));

		assertThat(remover.removeExpired()).isEqualTo(2);

		assertThat(api.statusOf(expired)).isNull();
		assertThat(api.statusOf(deleting)).isNull();
		assertThat(storage.contains(expiredKey)).isFalse();
		assertThat(storage.contains(deletingKey)).isFalse();
		assertThat(api.statusOf(ready)).isEqualTo("READY");
		assertThat(api.statusOf(uploading)).isEqualTo("UPLOADING");
		assertThat(remover.removeExpired()).isZero();
	}

	@Test
	@DisplayName("업로드 중에 지운 영상은 완료 기한 전에는 대상이 아니라 행이 남는다")
	void deletedWhileUploadingWaitsForDeadline() throws Exception {
		long id = api.startUploadId(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중에 지움");
		api.delete(f.choreographer(), id).andExpect(status().isNoContent());
		clock.advance(Duration.ofHours(6).minusSeconds(1));

		assertThat(remover.removeExpired()).isZero();
		assertThat(api.statusOf(id)).isEqualTo("DELETING");
	}

	/** 브라우저의 PUT은 서버와 따로 R2로 가서, 지운 뒤에 끝날 수 있다(PR A 리뷰). */
	@Test
	@DisplayName("업로드 중에 지운 뒤 진행 중이던 PUT이 끝나 객체가 생겨도, 완료 기한 뒤 청소 작업이 객체와 행을 지운다")
	void lateUploadAfterDeleteIsRemoved() throws Exception {
		ResultActions started = api
			.startUpload(f.choreographer(), f.performanceId(), uploadBody(f.choreoSong(), "올리는 중에 지움"))
			.andExpect(status().isCreated());
		long id = videoIdOf(started);
		String uploadUrl = uploadUrlOf(started);
		api.delete(f.choreographer(), id).andExpect(status().isNoContent());
		clock.advance(Duration.ofMinutes(20));
		String key = storage.uploadAsBrowser(uploadUrl);
		assertThat(storage.contains(key)).isTrue();

		clock.advance(Duration.ofHours(6));
		assertThat(remover.removeExpired()).isEqualTo(1);

		assertThat(api.statusOf(id)).isNull();
		assertThat(storage.contains(key)).isFalse();
	}

	@Test
	@DisplayName("한 영상에서 저장소가 실패해도 다른 영상은 처리되고, 실패한 영상은 다음에 처리된다")
	void failureIsolated() throws Exception {
		long first = deleting("첫째");
		long second = deleting("둘째");
		storage.failDeleteOnce(api.objectKeyOf(first));

		assertThat(remover.removeExpired()).isEqualTo(1);
		assertThat(api.statusOf(first)).isEqualTo("DELETING");
		assertThat(api.statusOf(second)).isNull();

		assertThat(remover.removeExpired()).isEqualTo(1);
		assertThat(api.statusOf(first)).isNull();
	}

	@RepeatedTest(10)
	@DisplayName("두 스레드에서 동시에 돌려도 한 영상을 두 번 처리하지 않는다(처리한 수의 합)")
	void concurrent() throws Exception {
		for (int i = 0; i < 5; i++) {
			deleting("지움 " + i);
		}

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return remover.removeExpired();
				}));
			}
			start.countDown();
			int total = 0;
			for (Future<Integer> future : futures) {
				total += future.get();
			}
			assertThat(total).isEqualTo(5);
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM practice_video", Long.class)).isZero();
	}

}
