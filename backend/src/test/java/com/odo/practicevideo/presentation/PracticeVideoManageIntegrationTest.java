package com.odo.practicevideo.presentation;

import java.util.List;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.practicevideo.presentation.PracticeVideoRequests.Fixture;
import com.odo.support.Concurrently;
import com.odo.support.DatabaseCleaner;
import com.odo.support.InMemoryObjectStorage;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 수정과 삭제. 08_PracticeVideo_spec.md의 "수정과 삭제 (통합)", 설계의 "삭제", API 명세의 "영상 수정", "영상 삭제".
 */
@IntegrationTest
class PracticeVideoManageIntegrationTest {

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

	private long ready() throws Exception {
		return api.uploadReady(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");
	}

	@Nested
	@DisplayName("수정")
	class Update {

		@Test
		@DisplayName("올린 디렉터가 고치면 200이고, 앞뒤 공백을 뺀 값이 저장되어 상세로 온다")
		void updated() throws Exception {
			long id = ready();

			api.update(f.choreographer(), id, "  정면 (수정)  ", "  0:40부터\n대형 이동  ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.title").value("정면 (수정)"))
				.andExpect(jsonPath("$.description").value("0:40부터\n대형 이동"));
			api.detail(f.dancer(), id).andExpect(jsonPath("$.title").value("정면 (수정)"));
		}

		@Test
		@DisplayName("설명을 비워 보내면 설명이 지워진다")
		void clearDescription() throws Exception {
			long id = ready();
			api.update(f.choreographer(), id, "정면", "설명").andExpect(status().isOk());

			api.update(f.choreographer(), id, "정면", "").andExpect(jsonPath("$.description").value(nullValue()));
		}

		@Test
		@DisplayName("같은 명단의 다른 디렉터, 팀장, 팀원은 403 NOT_PRACTICE_VIDEO_UPLOADER다")
		void notUploader() throws Exception {
			long id = ready();

			for (long other : new long[] { f.leader(), f.dancer() }) {
				api.update(other, id, "남이 고침", null)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("NOT_PRACTICE_VIDEO_UPLOADER"));
			}
		}

		@Test
		@DisplayName("디렉터 지정이 해제되어도 본인이 올린 영상은 고칠 수 있다")
		void unassignedDirector() throws Exception {
			long id = ready();
			api.setRole(f.performanceId(), f.choreographer(), "MEMBER");

			api.update(f.choreographer(), id, "정면 (수정)", null).andExpect(status().isOk());
		}

		@Test
		@DisplayName("업로드 중이면 409 PRACTICE_VIDEO_NOT_READY다")
		void uploading() throws Exception {
			long id = api.startUploadId(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중");

			api.update(f.choreographer(), id, "정면", null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_READY"));
		}

		@Test
		@DisplayName("제목과 설명이 규칙에 맞지 않으면 각자의 400이다")
		void invalid() throws Exception {
			long id = ready();

			api.update(f.choreographer(), id, " ", null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PRACTICE_VIDEO_TITLE"));
			api.update(f.choreographer(), id, "정면", "가".repeat(101))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PRACTICE_VIDEO_DESCRIPTION"));
		}

		@Test
		@DisplayName("명단에 없는 팀원은 404 PRACTICE_VIDEO_NOT_FOUND다")
		void notInRoster() throws Exception {
			long id = ready();

			api.update(f.bystander(), id, "정면", null)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
		}

	}

	@Nested
	@DisplayName("삭제")
	class Delete {

		@Test
		@DisplayName("올린 디렉터가 지우면 204이고, 객체와 영상 행이 지워지며 상세가 404다. 다시 지우면 404다")
		void deleted() throws Exception {
			long id = ready();
			String key = api.objectKeyOf(id);

			api.delete(f.choreographer(), id).andExpect(status().isNoContent());

			assertThat(api.statusOf(id)).isNull();
			assertThat(storage.wasDeleted(key)).isTrue();
			assertThat(storage.contains(key)).isFalse();
			api.detail(f.dancer(), id).andExpect(status().isNotFound());
			api.delete(f.choreographer(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
		}

		@Test
		@DisplayName("업로드 중인 영상도 지울 수 있다")
		void uploading() throws Exception {
			long id = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중");
			String key = api.objectKeyOf(id);

			api.delete(f.choreographer(), id).andExpect(status().isNoContent());

			assertThat(api.statusOf(id)).isNull();
			assertThat(storage.contains(key)).isFalse();
		}

		@Test
		@DisplayName("같은 명단의 다른 디렉터, 팀장, 팀원은 403 NOT_PRACTICE_VIDEO_UPLOADER이고, 명단 밖은 404다")
		void notUploader() throws Exception {
			long id = ready();

			for (long other : new long[] { f.leader(), f.dancer() }) {
				api.delete(other, id)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("NOT_PRACTICE_VIDEO_UPLOADER"));
			}
			api.delete(f.bystander(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
			assertThat(api.statusOf(id)).isEqualTo("READY");
		}

		@Test
		@DisplayName("객체 삭제가 실패해도 204이고 영상은 보이지 않으며, 행은 DELETING으로 남는다")
		void storageFailure() throws Exception {
			long id = ready();
			String key = api.objectKeyOf(id);
			storage.failDeleteOnce(key);

			api.delete(f.choreographer(), id).andExpect(status().isNoContent());

			assertThat(api.statusOf(id)).isEqualTo("DELETING");
			assertThat(storage.contains(key)).isTrue();
			api.detail(f.choreographer(), id).andExpect(status().isNotFound());
			api.update(f.choreographer(), id, "정면", null).andExpect(status().isNotFound());
			api.delete(f.choreographer(), id).andExpect(status().isNotFound());
		}

		@RepeatedTest(10)
		@DisplayName("동시에 두 번 지우면 하나는 204, 하나는 404다")
		void concurrent() throws Exception {
			long id = ready();

			List<Integer> statuses = Concurrently
				.statuses(Concurrently.run(2, () -> api.delete(f.choreographer(), id)));

			assertThat(statuses).containsExactlyInAnyOrder(204, 404);
			assertThat(api.statusOf(id)).isNull();
		}

	}

}
