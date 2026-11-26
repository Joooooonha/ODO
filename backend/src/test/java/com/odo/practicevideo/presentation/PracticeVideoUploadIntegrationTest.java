package com.odo.practicevideo.presentation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
import org.springframework.test.web.servlet.ResultActions;

import static com.odo.practicevideo.presentation.PracticeVideoRequests.FILE_SIZE;
import static com.odo.practicevideo.presentation.PracticeVideoRequests.MP4;
import static com.odo.practicevideo.presentation.PracticeVideoRequests.uploadBody;
import static com.odo.practicevideo.presentation.PracticeVideoRequests.uploadUrlOf;
import static com.odo.practicevideo.presentation.PracticeVideoRequests.videoIdOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 업로드 시작과 완료. 08_PracticeVideo_spec.md의 "업로드 (통합)", 설계의 "업로드", API 명세의 "업로드 시작", "업로드 완료".
 * 저장소는 가짜 구현이다. 시간은 MutableClock으로 옮긴다.
 */
@IntegrationTest
class PracticeVideoUploadIntegrationTest {

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

	@Nested
	@DisplayName("업로드 시작")
	class Start {

		@Test
		@DisplayName("자기 곡으로 시작하면 201, Location, UPLOADING, 업로드 주소와 Content-Type 헤더, 30분 뒤 만료 시각이다")
		void created() throws Exception {
			Map<String, Object> body = uploadBody(f.choreoSong(), "  Supernova 1절 정면  ");
			body.put("description", "0:40부터 대형 이동");

			ResultActions result = api.startUpload(f.choreographer(), f.performanceId(), body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.video.status").value("UPLOADING"))
				.andExpect(jsonPath("$.video.songId").value(f.choreoSong()))
				.andExpect(jsonPath("$.video.songName").value("Supernova"))
				.andExpect(jsonPath("$.video.title").value("Supernova 1절 정면"))
				.andExpect(jsonPath("$.video.uploaderAccountId").value(f.choreographer()))
				.andExpect(jsonPath("$.video.uploaderName").value("이민호"))
				.andExpect(jsonPath("$.video.mine").value(true))
				.andExpect(jsonPath("$.upload.url").value(startsWith(InMemoryObjectStorage.BASE_URL)))
				.andExpect(jsonPath("$.upload.method").value("PUT"))
				.andExpect(jsonPath("$.upload.headers.Content-Type").value(MP4))
				.andExpect(jsonPath("$.upload.expiresAt").value(clock.instant().plus(Duration.ofMinutes(30)).toString()));

			long id = videoIdOf(result);
			result.andExpect(header().string("Location", "/api/practice-videos/" + id));
			assertThat(api.statusOf(id)).isEqualTo("UPLOADING");
			assertThat(api.objectKeyOf(id)).startsWith("practice-videos/" + id + "/");
			assertThat(InMemoryObjectStorage.keyOf(uploadUrlOf(result))).isEqualTo(api.objectKeyOf(id));
		}

		@Test
		@DisplayName("mov도 받는다")
		void quicktime() throws Exception {
			Map<String, Object> body = uploadBody(f.choreoSong(), "정면");
			body.put("contentType", "video/quicktime");

			api.startUpload(f.choreographer(), f.performanceId(), body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.upload.headers.Content-Type").value("video/quicktime"));
		}

		@Test
		@DisplayName("명단에 없는 팀원은 404 PERFORMANCE_NOT_FOUND다")
		void notInRoster() throws Exception {
			api.startUpload(f.bystander(), f.performanceId(), uploadBody(f.choreoSong(), "정면"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
		}

		@Test
		@DisplayName("명단의 일반 팀원은 403 NOT_DIRECTOR다")
		void member() throws Exception {
			api.startUpload(f.dancer(), f.performanceId(), uploadBody(f.choreoSong(), "정면"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_DIRECTOR"));
		}

		@Test
		@DisplayName("디렉터 지정이 해제된 사람은 자기 곡이 있어도 403 NOT_DIRECTOR다")
		void unassignedDirector() throws Exception {
			api.setRole(f.performanceId(), f.choreographer(), "MEMBER");

			api.startUpload(f.choreographer(), f.performanceId(), uploadBody(f.choreoSong(), "정면"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_DIRECTOR"));
		}

		@Test
		@DisplayName("다른 디렉터가 담당하는 곡은 403 NOT_SONG_DIRECTOR다")
		void othersSong() throws Exception {
			api.startUpload(f.choreographer(), f.performanceId(), uploadBody(f.leaderSong(), "정면"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_SONG_DIRECTOR"));
		}

		@Test
		@DisplayName("삭제한 곡, 다른 공연의 곡, 없는 곡은 400 INVALID_SONG이다")
		void invalidSong() throws Exception {
			long otherPerformance = api.createPerformance(f.leader(), f.teamId(), "다른 공연",
					List.of(PracticeVideoRequests.member(f.leader(), "DIRECTOR"),
							PracticeVideoRequests.member(f.choreographer(), "DIRECTOR")));
			long otherSong = api.createSong(f.choreographer(), otherPerformance, "다른 곡");
			long deletedSong = api.createSong(f.choreographer(), f.performanceId(), "지울 곡");
			api.deleteSong(f.choreographer(), deletedSong);

			for (long songId : new long[] { deletedSong, otherSong, 999_999L }) {
				api.startUpload(f.choreographer(), f.performanceId(), uploadBody(songId, "정면"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_SONG"));
			}
		}

		@Test
		@DisplayName("songId, fileSize, contentType이 없으면 400 INVALID_REQUEST다")
		void missingRequired() throws Exception {
			for (String field : new String[] { "songId", "fileSize", "contentType" }) {
				Map<String, Object> body = uploadBody(f.choreoSong(), "정면");
				body.remove(field);
				api.startUpload(f.choreographer(), f.performanceId(), body)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			}
		}

		@Test
		@DisplayName("제목, 설명, 크기, 형식이 규칙에 맞지 않으면 각자의 400이다")
		void invalidInput() throws Exception {
			Map<String, Object> noTitle = uploadBody(f.choreoSong(), "   ");
			api.startUpload(f.choreographer(), f.performanceId(), noTitle)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PRACTICE_VIDEO_TITLE"));

			Map<String, Object> longDescription = uploadBody(f.choreoSong(), "정면");
			longDescription.put("description", "가".repeat(101));
			api.startUpload(f.choreographer(), f.performanceId(), longDescription)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PRACTICE_VIDEO_DESCRIPTION"));

			Map<String, Object> tooLarge = uploadBody(f.choreoSong(), "정면");
			tooLarge.put("fileSize", 1_073_741_825L);
			api.startUpload(f.choreographer(), f.performanceId(), tooLarge)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PRACTICE_VIDEO_FILE_SIZE"));

			Map<String, Object> webm = uploadBody(f.choreoSong(), "정면");
			webm.put("contentType", "video/webm");
			api.startUpload(f.choreographer(), f.performanceId(), webm)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_PRACTICE_VIDEO_TYPE"));

			assertThat(jdbc.queryForObject("SELECT count(*) FROM practice_video", Long.class)).isZero();
		}

		@Test
		@DisplayName("권한이 없는 사람이 틀린 제목을 보내도 권한 오류가 먼저 나온다")
		void permissionBeforeInput() throws Exception {
			api.startUpload(f.dancer(), f.performanceId(), uploadBody(f.choreoSong(), "   "))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_DIRECTOR"));

			api.startUpload(f.choreographer(), f.performanceId(), uploadBody(f.leaderSong(), "   "))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_SONG_DIRECTOR"));
		}

	}

	@Nested
	@DisplayName("업로드 완료")
	class Complete {

		@Test
		@DisplayName("서명한 크기와 형식 그대로 올라와 있으면 200, READY, 영상 상세다. 다시 불러도 200이다")
		void ready() throws Exception {
			long id = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");

			api.complete(f.choreographer(), id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.playback.url").value(startsWith(InMemoryObjectStorage.BASE_URL)));
			assertThat(api.statusOf(id)).isEqualTo("READY");

			api.complete(f.choreographer(), id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
		}

		@Test
		@DisplayName("R2에 파일이 없으면 409 UPLOADED_FILE_NOT_FOUND이고 영상 행은 남는다. 그 뒤 올리면 완료된다")
		void fileNotFound() throws Exception {
			ResultActions started = api.startUpload(f.choreographer(), f.performanceId(),
					uploadBody(f.choreoSong(), "정면"));
			long id = videoIdOf(started);

			api.complete(f.choreographer(), id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("UPLOADED_FILE_NOT_FOUND"));
			assertThat(api.statusOf(id)).isEqualTo("UPLOADING");

			storage.uploadAsBrowser(uploadUrlOf(started));
			api.complete(f.choreographer(), id).andExpect(status().isOk());
		}

		@Test
		@DisplayName("올라온 파일의 크기나 형식이 시작할 때와 다르면 409 UPLOADED_FILE_MISMATCH이고 객체와 영상 행이 지워진다")
		void mismatch() throws Exception {
			ResultActions sizeStarted = api.startUpload(f.choreographer(), f.performanceId(),
					uploadBody(f.choreoSong(), "크기 다름"));
			long sizeId = videoIdOf(sizeStarted);
			String sizeKey = storage.uploadAsBrowser(uploadUrlOf(sizeStarted), FILE_SIZE + 1, MP4);

			ResultActions typeStarted = api.startUpload(f.choreographer(), f.performanceId(),
					uploadBody(f.choreoSong(), "형식 다름"));
			long typeId = videoIdOf(typeStarted);
			String typeKey = storage.uploadAsBrowser(uploadUrlOf(typeStarted), FILE_SIZE, "video/quicktime");

			api.complete(f.choreographer(), sizeId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("UPLOADED_FILE_MISMATCH"));
			api.complete(f.choreographer(), typeId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("UPLOADED_FILE_MISMATCH"));

			assertThat(api.statusOf(sizeId)).isNull();
			assertThat(api.statusOf(typeId)).isNull();
			assertThat(storage.contains(sizeKey)).isFalse();
			assertThat(storage.contains(typeKey)).isFalse();
		}

		@Test
		@DisplayName("완료 기한(시작 + 6시간)이 지나면 409 UPLOAD_DEADLINE_PASSED다. 기한 직전에는 완료된다")
		void deadline() throws Exception {
			long late = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "늦음");
			long onTime = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "제때");

			clock.advance(Duration.ofHours(6).minusSeconds(1));
			api.complete(f.choreographer(), onTime).andExpect(status().isOk());

			clock.advance(Duration.ofSeconds(2));
			api.complete(f.choreographer(), late)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("UPLOAD_DEADLINE_PASSED"));
			assertThat(api.statusOf(late)).isEqualTo("UPLOADING");
		}

		@Test
		@DisplayName("올린 디렉터가 아닌 사람은 404 PRACTICE_VIDEO_NOT_FOUND다(업로드 중인 영상은 그 사람에게 없다)")
		void notUploader() throws Exception {
			long id = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");

			for (long other : new long[] { f.leader(), f.dancer(), f.bystander() }) {
				api.complete(other, id)
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
			}
			assertThat(api.statusOf(id)).isEqualTo("UPLOADING");
		}

		@Test
		@DisplayName("시작한 뒤 디렉터 지정이 해제되면 403 NOT_DIRECTOR다")
		void unassignedAfterStart() throws Exception {
			long id = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");
			api.setRole(f.performanceId(), f.choreographer(), "MEMBER");

			api.complete(f.choreographer(), id)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_DIRECTOR"));
		}

		@Test
		@DisplayName("시작한 뒤 곡이 삭제되어도 완료된다")
		void songDeletedAfterStart() throws Exception {
			long id = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");
			api.deleteSong(f.choreographer(), f.choreoSong());

			api.complete(f.choreographer(), id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.songName").value("Supernova"));
		}

		@Test
		@DisplayName("저장소에 닿지 못하면 503 STORAGE_UNAVAILABLE이고 영상 행은 남는다. 저장소가 돌아오면 완료된다")
		void storageUnavailable() throws Exception {
			long id = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");
			storage.failFind(true);

			api.complete(f.choreographer(), id)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
			assertThat(api.statusOf(id)).isEqualTo("UPLOADING");

			storage.failFind(false);
			api.complete(f.choreographer(), id).andExpect(status().isOk());
		}

		@RepeatedTest(10)
		@DisplayName("동시에 두 번 부르면 둘 다 200이고 READY 하나다")
		void concurrent() throws Exception {
			long id = api.startAndUpload(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");

			List<Integer> statuses = Concurrently
				.statuses(Concurrently.run(2, () -> api.complete(f.choreographer(), id)));

			assertThat(statuses).containsExactly(200, 200);
			assertThat(api.statusOf(id)).isEqualTo("READY");
		}

	}

}
