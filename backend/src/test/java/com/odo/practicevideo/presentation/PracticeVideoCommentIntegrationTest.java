package com.odo.practicevideo.presentation;

import java.time.Instant;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.practicevideo.presentation.PracticeVideoRequests.Fixture;
import com.odo.support.DatabaseCleaner;
import com.odo.support.InMemoryObjectStorage;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시점 코멘트. 08_PracticeVideo_spec.md의 "코멘트 (단위와 통합)", 설계의 "시점 코멘트",
 * API 명세의 "코멘트 목록", "스레드 만들기", "스레드에 답하기".
 */
@IntegrationTest
class PracticeVideoCommentIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-11-26T11:00:00Z");

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
	PlatformTransactionManager transactionManager;

	private PracticeVideoRequests api;

	private Fixture f;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.set(T0);
		storage.reset();
		api = new PracticeVideoRequests(mockMvc, accessTokenIssuer, jdbc, storage);
		f = api.setUpFixture(clock);
	}

	private long ready() throws Exception {
		return api.uploadReady(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");
	}

	@Nested
	@DisplayName("스레드 만들기와 답하기")
	class CreateAndReply {

		@Test
		@DisplayName("스레드를 만들면 201이고, 그 시점과 첫 코멘트가 온다. Location은 없다")
		void create() throws Exception {
			long id = ready();

			api.createThread(f.dancer(), id, 42, "  여기 팔 각도가 어떻게 돼요?  ")
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.positionSeconds").value(42))
				.andExpect(jsonPath("$.createdAt").value("2026-11-26T11:00:00Z"))
				.andExpect(jsonPath("$.comments", hasSize(1)))
				.andExpect(jsonPath("$.comments[0].id").isNumber())
				.andExpect(jsonPath("$.comments[0].authorAccountId").value(f.dancer()))
				.andExpect(jsonPath("$.comments[0].authorName").value("김지수"))
				.andExpect(jsonPath("$.comments[0].content").value("여기 팔 각도가 어떻게 돼요?"))
				.andExpect(jsonPath("$.comments[0].createdAt").value("2026-11-26T11:00:00Z"))
				.andExpect(jsonPath("$.comments[0].mine").value(true));
		}

		@Test
		@DisplayName("답하면 201이고 그 스레드에 들어간다. 시점은 스레드의 시점이다")
		void reply() throws Exception {
			long id = ready();
			long thread = api.createThreadId(f.dancer(), id, 42, "여기 팔 각도가 어떻게 돼요?");
			clock.set(T0.plusSeconds(300));

			api.reply(f.choreographer(), thread, "45도로 들어요 🙆")
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.authorAccountId").value(f.choreographer()))
				.andExpect(jsonPath("$.authorName").value("이민호"))
				.andExpect(jsonPath("$.content").value("45도로 들어요 🙆"))
				.andExpect(jsonPath("$.createdAt").value("2026-11-26T11:05:00Z"))
				.andExpect(jsonPath("$.mine").value(true));

			api.threads(f.dancer(), id)
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(thread))
				.andExpect(jsonPath("$[0].positionSeconds").value(42))
				.andExpect(jsonPath("$[0].comments[*].content").value(contains("여기 팔 각도가 어떻게 돼요?", "45도로 들어요 🙆")));
		}

		@Test
		@DisplayName("같은 초에 스레드를 두 번 만들면 두 스레드가 된다")
		void sameSecondTwoThreads() throws Exception {
			long id = ready();

			long first = api.createThreadId(f.dancer(), id, 42, "팔 각도");
			long second = api.createThreadId(f.leader(), id, 42, "시선 처리");

			assertThat(first).isNotEqualTo(second);
			api.threads(f.dancer(), id)
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[*].positionSeconds").value(contains(42, 42)));
		}

		@Test
		@DisplayName("영상 길이를 모르므로 0초와 큰 시점을 받는다")
		void positionBounds() throws Exception {
			long id = ready();

			api.createThread(f.dancer(), id, 0, "처음").andExpect(status().isCreated());
			api.createThread(f.dancer(), id, 36_000, "끝").andExpect(status().isCreated());
		}

	}

	@Nested
	@DisplayName("코멘트 목록")
	class ListThreads {

		@Test
		@DisplayName("스레드는 시점 순, 같은 시점은 만든 순서, 스레드 안은 단 순서다. mine은 보는 사람 기준이다")
		void ordered() throws Exception {
			long id = ready();
			long laterAt30 = api.createThreadId(f.dancer(), id, 30, "30초 첫째");
			clock.set(T0.plusSeconds(10));
			long at10 = api.createThreadId(f.leader(), id, 10, "10초");
			clock.set(T0.plusSeconds(20));
			long secondAt30 = api.createThreadId(f.choreographer(), id, 30, "30초 둘째");
			clock.set(T0.plusSeconds(30));
			api.reply(f.choreographer(), laterAt30, "답 하나").andExpect(status().isCreated());
			clock.set(T0.plusSeconds(40));
			api.reply(f.dancer(), laterAt30, "답 둘").andExpect(status().isCreated());

			api.threads(f.dancer(), id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].id").value(contains((int) at10, (int) laterAt30, (int) secondAt30)))
				.andExpect(jsonPath("$[1].comments[*].content").value(contains("30초 첫째", "답 하나", "답 둘")))
				.andExpect(jsonPath("$[1].comments[*].mine").value(contains(true, false, true)))
				.andExpect(jsonPath("$[0].comments[0].mine").value(false));
		}

		@Test
		@DisplayName("코멘트가 없으면 빈 목록이다")
		void empty() throws Exception {
			long id = ready();

			api.threads(f.dancer(), id).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
		}

	}

	@Nested
	@DisplayName("입력 오류")
	class Invalid {

		@Test
		@DisplayName("positionSeconds가 음수면 400 INVALID_COMMENT_POSITION, 없으면 400 INVALID_REQUEST다")
		void position() throws Exception {
			long id = ready();

			api.createThread(f.dancer(), id, -1, "팔 각도")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_COMMENT_POSITION"));
			api.createThread(f.dancer(), id, null, "팔 각도")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			assertThat(api.countRows("practice_video_thread", id)).isZero();
		}

		@Test
		@DisplayName("내용이 없거나, 공백만 있거나, 300자를 넘거나, 제어 문자가 있으면 400 INVALID_COMMENT_CONTENT다")
		void content() throws Exception {
			long id = ready();
			long thread = api.createThreadId(f.dancer(), id, 42, "팔 각도");

			for (String content : new String[] { null, "", "   ", "가".repeat(301), "NUL\u0000" }) {
				api.createThread(f.dancer(), id, 10, content)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_COMMENT_CONTENT"));
				api.reply(f.dancer(), thread, content)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_COMMENT_CONTENT"));
			}
			assertThat(api.countRows("practice_video_thread", id)).isEqualTo(1);
			assertThat(api.countRows("practice_video_comment", id)).isEqualTo(1);
		}

	}

	@Nested
	@DisplayName("볼 수 없는 영상과 스레드")
	class NotVisible {

		@Test
		@DisplayName("명단에 없는 팀원은 목록과 만들기가 404 PRACTICE_VIDEO_NOT_FOUND, 답하기가 404 PRACTICE_VIDEO_THREAD_NOT_FOUND다")
		void notInRoster() throws Exception {
			long id = ready();
			long thread = api.createThreadId(f.dancer(), id, 42, "팔 각도");

			api.threads(f.bystander(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
			api.createThread(f.bystander(), id, 10, "몰래")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
			api.reply(f.bystander(), thread, "몰래")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_THREAD_NOT_FOUND"));
			assertThat(api.countRows("practice_video_comment", id)).isEqualTo(1);
		}

		/**
		 * 잠그지 않으면 READY로 읽은 뒤 스레드를 넣다가, 삭제가 커밋되면 FK 위반으로 500이 된다(PR B 리뷰).
		 * 확인할 때 영상 행을 잠그면 삭제의 커밋을 기다렸다가 행이 없어 404다.
		 */
		@Test
		@DisplayName("삭제가 영상 행을 지우고 커밋하기 전에 스레드를 만들면, 기다렸다가 404 PRACTICE_VIDEO_NOT_FOUND이고 스레드와 코멘트가 남지 않는다")
		void createWhileRowDeleted() throws Exception {
			long id = ready();

			api.whileVideoRowDeleted(transactionManager, id, () -> api.createThread(f.dancer(), id, 42, "팔 각도"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
			assertThat(api.countRows("practice_video_thread", id)).isZero();
			assertThat(api.countRows("practice_video_comment", id)).isZero();
		}

		@Test
		@DisplayName("삭제가 영상 행을 지우고 커밋하기 전에 답하면, 기다렸다가 404 PRACTICE_VIDEO_THREAD_NOT_FOUND이고 코멘트가 남지 않는다")
		void replyWhileRowDeleted() throws Exception {
			long id = ready();
			long thread = api.createThreadId(f.dancer(), id, 42, "팔 각도");

			api.whileVideoRowDeleted(transactionManager, id, () -> api.reply(f.choreographer(), thread, "45도"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_THREAD_NOT_FOUND"));
			assertThat(api.countRows("practice_video_thread", id)).isZero();
			assertThat(api.countRows("practice_video_comment", id)).isZero();
		}

		@Test
		@DisplayName("없는 스레드에 답하면 404 PRACTICE_VIDEO_THREAD_NOT_FOUND다")
		void missingThread() throws Exception {
			api.reply(f.dancer(), 999_999L, "답")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_THREAD_NOT_FOUND"));
		}

		@Test
		@DisplayName("업로드 중인 영상은 올린 디렉터에게 목록과 만들기가 409 PRACTICE_VIDEO_NOT_READY, 다른 사람에게 404다")
		void uploading() throws Exception {
			long id = api.startUploadId(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중");

			api.threads(f.choreographer(), id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_READY"));
			api.createThread(f.choreographer(), id, 10, "올리는 중")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_READY"));
			api.threads(f.dancer(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
		}

		@Test
		@DisplayName("객체 삭제가 실패해 삭제 중(DELETING)으로 남은 영상의 스레드에 답하면 404 PRACTICE_VIDEO_THREAD_NOT_FOUND다")
		void deletingVideo() throws Exception {
			long id = ready();
			long thread = api.createThreadId(f.dancer(), id, 42, "팔 각도");
			storage.failDeleteOnce(api.objectKeyOf(id));
			api.delete(f.choreographer(), id).andExpect(status().isNoContent());
			assertThat(api.statusOf(id)).isEqualTo("DELETING");

			api.reply(f.dancer(), thread, "답")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_THREAD_NOT_FOUND"));
			api.threads(f.dancer(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
		}

	}

	@Test
	@DisplayName("영상을 지우면 그 영상의 스레드와 코멘트, 시청 기록이 함께 지워지고, 지운 영상의 스레드에 답하면 404다")
	void cascadeOnDelete() throws Exception {
		long id = ready();
		long kept = ready();
		long thread = api.createThreadId(f.dancer(), id, 42, "팔 각도");
		api.reply(f.choreographer(), thread, "45도").andExpect(status().isCreated());
		api.recordView(f.dancer(), id).andExpect(status().isNoContent());
		api.createThreadId(f.dancer(), kept, 10, "남는 영상");
		api.recordView(f.dancer(), kept).andExpect(status().isNoContent());

		api.delete(f.choreographer(), id).andExpect(status().isNoContent());

		assertThat(api.statusOf(id)).isNull();
		assertThat(api.countRows("practice_video_thread", id)).isZero();
		assertThat(api.countRows("practice_video_comment", id)).isZero();
		assertThat(api.countRows("practice_video_view", id)).isZero();
		api.reply(f.dancer(), thread, "답")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_THREAD_NOT_FOUND"));
		assertThat(api.countRows("practice_video_thread", kept)).isEqualTo(1);
		assertThat(api.countRows("practice_video_view", kept)).isEqualTo(1);
	}

}
