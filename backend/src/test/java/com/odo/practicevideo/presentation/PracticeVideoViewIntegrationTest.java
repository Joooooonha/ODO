package com.odo.practicevideo.presentation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.practicevideo.presentation.PracticeVideoRequests.Fixture;
import com.odo.support.DatabaseCleaner;
import com.odo.support.InMemoryObjectStorage;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.support.TestTeams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import static com.odo.practicevideo.presentation.PracticeVideoRequests.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시청 확인과 현황. 08_PracticeVideo_spec.md의 "시청 확인과 현황 (통합)", 설계의 "대상자와 시청 확인",
 * API 명세의 "시청 확인 기록", "시청 현황".
 * 명단: leader(디렉터), choreographer(디렉터, 영상을 올림), dancer(팀원). 대상자는 choreographer를 뺀 사람이다.
 * 계정 ID는 1000부터 시작한다. ID를 Long끼리 ==, !=로 비교하면 -128~127에서만 값이 같을 때 같다고 나와, 작은 ID로는
 * 그 실수가 드러나지 않는다(PR B 리뷰).
 */
@IntegrationTest
class PracticeVideoViewIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-11-26T10:00:00Z");

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
		jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 1000");
		clock.set(T0);
		storage.reset();
		api = new PracticeVideoRequests(mockMvc, accessTokenIssuer, jdbc, storage);
		f = api.setUpFixture(clock);
	}

	private long ready() throws Exception {
		return api.uploadReady(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");
	}

	/** 팀에 넣고 공연 명단에 팀원(MEMBER)으로 넣은 사람. */
	private long joinRoster(String name, int generation) throws Exception {
		long accountId = TestAccounts.create(jdbc, name, clock.instant());
		TestTeams.addMember(jdbc, f.teamId(), accountId, generation, clock.instant());
		api.addToRoster(f.leader(), f.performanceId(), List.of(member(accountId, "MEMBER")));
		return accountId;
	}

	@Nested
	@DisplayName("시청 확인 기록")
	class Record {

		@Test
		@DisplayName("대상자가 기록하면 204이고, 다시 기록해도 처음 시각이 남는다")
		void firstTimeKept() throws Exception {
			long id = ready();
			clock.set(T0.plusSeconds(60));

			api.recordView(f.dancer(), id).andExpect(status().isNoContent());
			clock.advance(Duration.ofHours(1));
			api.recordView(f.dancer(), id).andExpect(status().isNoContent());

			api.views(f.choreographer(), id)
				.andExpect(jsonPath("$.viewed[0].accountId").value(f.dancer()))
				.andExpect(jsonPath("$.viewed[0].viewedAt").value("2026-11-26T10:01:00Z"));
			assertThat(api.countRows("practice_video_view", id)).isEqualTo(1);
		}

		@Test
		@DisplayName("올린 디렉터가 기록해도 204이고 행이 생기지 않는다")
		void uploaderNotRecorded() throws Exception {
			long id = ready();

			api.recordView(f.choreographer(), id).andExpect(status().isNoContent());

			assertThat(api.countRows("practice_video_view", id)).isZero();
		}

		@Test
		@DisplayName("업로드 중인 영상에 기록하면 올린 디렉터는 409 PRACTICE_VIDEO_NOT_READY, 다른 사람은 404다")
		void uploading() throws Exception {
			long id = api.startUploadId(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중");

			api.recordView(f.choreographer(), id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_READY"));
			api.recordView(f.dancer(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
		}

		@Test
		@DisplayName("명단에 없는 팀원은 404 PRACTICE_VIDEO_NOT_FOUND이고 기록되지 않는다")
		void notInRoster() throws Exception {
			long id = ready();

			api.recordView(f.bystander(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
			assertThat(api.countRows("practice_video_view", id)).isZero();
		}

		/**
		 * 잠그지 않으면 READY로 읽은 뒤 기록을 넣다가, 삭제가 커밋되면 FK 위반으로 500이 된다(PR B 리뷰).
		 * 확인할 때 영상 행을 잠그면 삭제의 커밋을 기다렸다가 행이 없어 404다.
		 */
		@Test
		@DisplayName("삭제가 영상 행을 지우고 커밋하기 전에 기록하면, 기다렸다가 404 PRACTICE_VIDEO_NOT_FOUND이고 기록이 남지 않는다")
		void whileRowDeleted() throws Exception {
			long id = ready();

			api.whileVideoRowDeleted(transactionManager, id, () -> api.recordView(f.dancer(), id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
			assertThat(api.countRows("practice_video_view", id)).isZero();
		}

	}

	@Nested
	@DisplayName("시청 현황")
	class Status {

		@Test
		@DisplayName("본 사람은 먼저 본 순서, 안 본 사람은 명단 순서(디렉터 먼저, 들어온 순서)이고 이름과 기수가 온다")
		void ordered() throws Exception {
			long first = joinRoster("정하늘", 26);
			long second = joinRoster("한별", 27);
			long id = ready();
			clock.set(T0.plusSeconds(60));
			api.recordView(second, id).andExpect(status().isNoContent());
			clock.set(T0.plusSeconds(120));
			api.recordView(f.dancer(), id).andExpect(status().isNoContent());

			api.views(f.choreographer(), id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targetCount").value(4))
				.andExpect(jsonPath("$.viewedCount").value(2))
				.andExpect(jsonPath("$.viewed[*].accountId").value(contains((int) second, (int) f.dancer())))
				.andExpect(jsonPath("$.viewed[0].name").value("한별"))
				.andExpect(jsonPath("$.viewed[0].generation").value(27))
				.andExpect(jsonPath("$.viewed[0].viewedAt").value("2026-11-26T10:01:00Z"))
				.andExpect(jsonPath("$.notViewed[*].accountId").value(contains((int) f.leader(), (int) first)))
				.andExpect(jsonPath("$.notViewed[1].name").value("정하늘"))
				.andExpect(jsonPath("$.notViewed[1].generation").value(26));
		}

		@Test
		@DisplayName("영상을 올린 뒤 명단에 추가된 사람이 안 본 사람으로 잡힌다")
		void joinedAfterUpload() throws Exception {
			long id = ready();
			long late = joinRoster("늦게온사람", 27);

			api.views(f.choreographer(), id)
				.andExpect(jsonPath("$.targetCount").value(3))
				.andExpect(jsonPath("$.viewed", hasSize(0)))
				.andExpect(jsonPath("$.notViewed[*].accountId")
					.value(contains((int) f.leader(), (int) f.dancer(), (int) late)));
		}

		@Test
		@DisplayName("올린 디렉터만 본다. 같은 명단의 다른 디렉터와 팀원은 403 NOT_PRACTICE_VIDEO_UPLOADER, 명단 밖은 404다")
		void uploaderOnly() throws Exception {
			long id = ready();

			for (long other : new long[] { f.leader(), f.dancer() }) {
				api.views(other, id)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("NOT_PRACTICE_VIDEO_UPLOADER"));
			}
			api.views(f.bystander(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
		}

		@Test
		@DisplayName("업로드 중이면 409 PRACTICE_VIDEO_NOT_READY다")
		void uploading() throws Exception {
			long id = api.startUploadId(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중");

			api.views(f.choreographer(), id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_READY"));
		}

	}

	@Nested
	@DisplayName("viewedByMe")
	class ViewedByMe {

		@Test
		@DisplayName("목록과 상세에서 기록 전 false, 기록 뒤 true이고, 내가 올린 영상은 null이다")
		void listAndDetail() throws Exception {
			long id = ready();

			api.list(f.dancer(), f.performanceId()).andExpect(jsonPath("$.items[0].viewedByMe").value(false));
			api.detail(f.dancer(), id).andExpect(jsonPath("$.viewedByMe").value(false));

			api.recordView(f.dancer(), id).andExpect(status().isNoContent());

			api.list(f.dancer(), f.performanceId()).andExpect(jsonPath("$.items[0].viewedByMe").value(true));
			api.detail(f.dancer(), id).andExpect(jsonPath("$.viewedByMe").value(true));
			api.list(f.leader(), f.performanceId()).andExpect(jsonPath("$.items[0].viewedByMe").value(false));
			api.list(f.choreographer(), f.performanceId())
				.andExpect(jsonPath("$.items[0].viewedByMe").value(nullValue()));
			api.detail(f.choreographer(), id).andExpect(jsonPath("$.viewedByMe").value(nullValue()));
		}

	}

}
