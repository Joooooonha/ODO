package SeSAC.Dance_Assessment.Team;

import SeSAC.Dance_Assessment.Infrastructure.PracticeLogRepository;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자유 연습 영상·기록은 본인만 (작업 지시서 4.6, 8절 D4).
 *
 * <p>예전에는 같은 팀원이 올린 기준 영상을 재생할 수 있었다. 그 분기를 없앴으므로
 * <b>같은 팀으로 묶은 뒤에도</b> 거부되는지를 본다 — 팀이 없는 두 사람으로 확인하면
 * 예전 코드에서도 통과해 아무것도 증명하지 못한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class VideoAccessTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PracticeLogRepository practiceLogRepository;

    TestUsers users;
    LoggedIn owner;
    LoggedIn teammate;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(mvc, objectMapper);
        owner = users.create("영상 주인");
        teammate = users.create("같은 팀원");

        // 같은 팀으로 묶는다
        String team = mvc.perform(post("/api/teams").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "영상 팀"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long teamId = objectMapper.readTree(team).get("teamId").asLong();
        String code = objectMapper.readTree(mvc.perform(get("/api/teams/{id}/join-code", teamId)
                        .cookie(owner.cookie()))
                .andReturn().getResponse().getContentAsString()).get("joinCode").asText();
        mvc.perform(post("/api/teams/join").cookie(teammate.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("본인 영상은 재생되고(302), 같은 팀원의 기준·연습 영상은 거부된다(403)")
    void onlyOwnerCanPlay() throws Exception {
        long reference = upload(owner, "REFERENCE", null).get("videoId").asLong();
        long practice = upload(owner, "PRACTICE", reference).get("videoId").asLong();

        mvc.perform(get("/api/videos/{id}", reference).cookie(owner.cookie()))
                .andExpect(status().isFound());
        mvc.perform(get("/api/videos/{id}", practice).cookie(owner.cookie()))
                .andExpect(status().isFound());

        mvc.perform(get("/api/videos/{id}", reference).cookie(teammate.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/videos/{id}", practice).cookie(teammate.cookie()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("같은 팀원의 연습 기록 상세는 거부된다 (기존대로 400)")
    void practiceLogIsOwnerOnly() throws Exception {
        long reference = upload(owner, "REFERENCE", null).get("videoId").asLong();
        long logId = upload(owner, "PRACTICE", reference).get("logId").asLong();

        mvc.perform(get("/api/practice-logs/{id}", logId).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceVideoId").value(reference));
        mvc.perform(get("/api/practice-logs/{id}", logId).cookie(teammate.cookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("남의 영상을 referenceVideoId로 넣은 업로드 완료는 거부되고 연습 기록이 생기지 않는다")
    void cannotUseOthersVideoAsReference() throws Exception {
        long othersReference = upload(owner, "REFERENCE", null).get("videoId").asLong();
        long before = practiceLogRepository.count();

        long practice = createUploadUrl(teammate, "PRACTICE");
        mvc.perform(post("/api/videos/{id}/complete", practice).cookie(teammate.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("referenceVideoId", othersReference))))
                .andExpect(status().isBadRequest());

        assertThat(practiceLogRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("[회귀] 자유 연습: 내 기준 영상으로 업로드하면 기록이 생기고, 목록에 보이고, 분석 요청이 접수된다")
    void freePracticeStillWorks() throws Exception {
        long reference = upload(owner, "REFERENCE", null).get("videoId").asLong();

        mvc.perform(get("/api/videos").param("type", "REFERENCE").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].videoId").value(reference));

        JsonNode done = upload(owner, "PRACTICE", reference);
        assertThat(done.get("analyzable").asBoolean()).isTrue();
        long logId = done.get("logId").asLong();

        mvc.perform(get("/api/practice-logs").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].logId").value(logId));
        mvc.perform(post("/api/practice-logs/{id}/analyze", logId).cookie(owner.cookie()))
                .andExpect(status().isAccepted());
    }

    private long createUploadUrl(LoggedIn user, String type) throws Exception {
        String body = mvc.perform(post("/api/videos/upload-url").cookie(user.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "filename", "dance.mp4", "contentType", "video/mp4", "type", type))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("videoId").asLong();
    }

    /** 업로드 URL 발급 → (저장소 PUT은 생략) → 완료 통보. 완료 응답을 돌려준다. */
    private JsonNode upload(LoggedIn user, String type, Long referenceVideoId) throws Exception {
        long videoId = createUploadUrl(user, type);
        Map<String, Object> link = new HashMap<>();
        if (referenceVideoId != null) {
            link.put("referenceVideoId", referenceVideoId);
        }
        String body = mvc.perform(post("/api/videos/{id}/complete", videoId).cookie(user.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(link)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
