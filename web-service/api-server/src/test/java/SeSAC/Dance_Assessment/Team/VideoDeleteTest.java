package SeSAC.Dance_Assessment.Team;

import SeSAC.Dance_Assessment.Domain.Video;
import SeSAC.Dance_Assessment.Infrastructure.VideoRepository;
import SeSAC.Dance_Assessment.Storage.StorageService;
import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 삭제 API (D16).
 *
 * <p>예전 코드는 {@code videoRepository.delete} 직후 저장소 파일을 지웠다. JPA는 DELETE를 커밋할 때
 * 실행하므로(쓰기 지연) 실제 순서는 "파일 삭제 → DELETE"였고, DELETE가 FK 위반으로 롤백되면
 * <b>DB 행은 있는데 파일은 없는</b> 상태가 됐다. 그 상황이 생기지 않는지를 파일까지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class VideoDeleteTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    StorageService storage;

    TestTeams t;
    LoggedIn owner;

    @BeforeEach
    void setUp() throws Exception {
        t = new TestTeams(mvc, om);
        owner = new TestUsers(mvc, om).create("삭제테스트");
    }

    @Test
    @DisplayName("[핵심] 연습 기록이 참조하는 영상은 409로 거부되고, DB 행과 저장소 파일이 모두 남는다")
    void referencedVideoKeepsRowAndFile() throws Exception {
        long reference = t.upload(owner, "REFERENCE", null, null).get("videoId").asLong();
        t.upload(owner, "PRACTICE", null, reference); // 이 기준 영상을 참조하는 연습 기록이 생긴다
        String key = videoRepository.findById(reference).map(Video::getObjectKey).orElseThrow();
        assertThat(download(key)).isEqualTo(200);

        mvc.perform(delete("/api/videos/{id}", reference).cookie(owner.cookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIDEO_IN_USE"));

        assertThat(videoRepository.findById(reference)).as("DB 행이 남아야 한다").isPresent();
        assertThat(download(key)).as("저장소 파일도 남아야 한다").isEqualTo(200);
        mvc.perform(get("/api/videos/{id}", reference).cookie(owner.cookie()))
                .andExpect(status().isFound());
    }

    @Test
    @DisplayName("Mission 영상은 삭제를 거부한다 (미션·제출이 FK로 참조하므로)")
    void missionVideoIsNotDeletable() throws Exception {
        long mission = t.missionReference(owner);
        String key = videoRepository.findById(mission).map(Video::getObjectKey).orElseThrow();

        mvc.perform(delete("/api/videos/{id}", mission).cookie(owner.cookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISSION_VIDEO_NOT_DELETABLE"));
        assertThat(videoRepository.findById(mission)).isPresent();
        assertThat(download(key)).isEqualTo(200);
    }

    @Test
    @DisplayName("[회귀] 아무도 참조하지 않는 자유 연습 영상은 DB 행과 파일이 함께 지워진다")
    void unreferencedVideoIsDeleted() throws Exception {
        long video = t.upload(owner, "REFERENCE", null, null).get("videoId").asLong();
        String key = videoRepository.findById(video).map(Video::getObjectKey).orElseThrow();

        mvc.perform(delete("/api/videos/{id}", video).cookie(owner.cookie()))
                .andExpect(status().isOk());

        assertThat(videoRepository.findById(video)).isEmpty();
        assertThat(download(key)).isEqualTo(404);
    }

    /** 저장소에서 그 키의 파일을 받아본다. 있으면 200, 없으면 404. */
    private int download(String key) throws Exception {
        Map<String, String> q = Arrays.stream(URI.create(storage.createDownloadUrl(key)).getQuery().split("&"))
                .map(kv -> kv.split("=", 2))
                .collect(Collectors.toMap(a -> a[0], a -> URLDecoder.decode(a[1], StandardCharsets.UTF_8)));
        return mvc.perform(get("/api/local-storage")
                        .param("key", q.get("key")).param("method", "GET")
                        .param("exp", q.get("exp")).param("sig", q.get("sig")))
                .andReturn().getResponse().getStatus();
    }
}
