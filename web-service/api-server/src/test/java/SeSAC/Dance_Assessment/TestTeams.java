package SeSAC.Dance_Assessment;

import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 팀·영상·미션 테스트의 공통 준비 — 모두 실제 API를 거친다.
 * 사용자는 {@link TestUsers}로 매번 새로 만든다(테스트 DB를 전체가 공유하므로).
 */
public final class TestTeams {

    private final MockMvc mvc;
    private final ObjectMapper om;

    public TestTeams(MockMvc mvc, ObjectMapper om) {
        this.mvc = mvc;
        this.om = om;
    }

    public Long createTeam(LoggedIn leader, String name) throws Exception {
        return json(mvc.perform(post("/api/teams").cookie(leader.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())).get("teamId").asLong();
    }

    /** 팀장이 팀을 만들고 나머지가 참여한다. 팀 id를 돌려준다. */
    public Long teamOf(LoggedIn leader, LoggedIn... members) throws Exception {
        Long teamId = createTeam(leader, "팀-" + leader.nickname());
        String code = json(mvc.perform(get("/api/teams/{id}/join-code", teamId).cookie(leader.cookie()))
                .andExpect(status().isOk())).get("joinCode").asText();
        for (LoggedIn m : members) {
            mvc.perform(post("/api/teams/join").cookie(m.cookie())
                            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("code", code))))
                    .andExpect(status().isOk());
        }
        return teamId;
    }

    public String joinCode(LoggedIn leader, Long teamId) throws Exception {
        return json(mvc.perform(get("/api/teams/{id}/join-code", teamId).cookie(leader.cookie()))
                .andExpect(status().isOk())).get("joinCode").asText();
    }

    public void leave(LoggedIn member, Long teamId) throws Exception {
        mvc.perform(post("/api/teams/{id}/leave", teamId).cookie(member.cookie()))
                .andExpect(status().isNoContent());
    }

    public void kick(LoggedIn leader, Long teamId, LoggedIn member) throws Exception {
        mvc.perform(post("/api/teams/{id}/members/{uid}/kick", teamId, member.id()).cookie(leader.cookie()))
                .andExpect(status().isNoContent());
    }

    public void rejoin(LoggedIn member, String code) throws Exception {
        mvc.perform(post("/api/teams/join").cookie(member.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk());
    }

    // ===== 영상 =====

    /** 업로드 URL 발급 → 저장소 PUT → 완료 통보. 완료 응답을 돌려준다. */
    public JsonNode upload(LoggedIn user, String type, String purpose, Long referenceVideoId) throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of(
                "filename", "clip.mp4", "contentType", "video/mp4", "type", type));
        if (purpose != null) {
            req.put("purpose", purpose);
        }
        JsonNode issued = json(mvc.perform(post("/api/videos/upload-url").cookie(user.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(req)))
                .andExpect(status().isOk()));
        putToLocalStorage(issued.get("uploadUrl").asText(), ("video-" + issued.get("videoId").asLong()).getBytes());

        Map<String, Object> link = new HashMap<>();
        if (referenceVideoId != null) {
            link.put("referenceVideoId", referenceVideoId);
        }
        return json(mvc.perform(post("/api/videos/{id}/complete", issued.get("videoId").asLong()).cookie(user.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(link)))
                .andExpect(status().isOk()));
    }

    public Long missionReference(LoggedIn leader) throws Exception {
        return upload(leader, "REFERENCE", "MISSION", null).get("videoId").asLong();
    }

    public Long missionSubmissionVideo(LoggedIn member) throws Exception {
        return upload(member, "PRACTICE", "MISSION", null).get("videoId").asLong();
    }

    private void putToLocalStorage(String uploadUrl, byte[] body) throws Exception {
        Map<String, String> q = Arrays.stream(URI.create(uploadUrl).getQuery().split("&"))
                .map(kv -> kv.split("=", 2))
                .collect(Collectors.toMap(a -> a[0], a -> URLDecoder.decode(a[1], StandardCharsets.UTF_8)));
        mvc.perform(put("/api/local-storage")
                        .param("key", q.get("key")).param("method", "PUT")
                        .param("exp", q.get("exp")).param("sig", q.get("sig"))
                        .content(body))
                .andExpect(status().isOk());
    }

    // ===== 미션 =====

    public ResultActions createMissionRequest(LoggedIn leader, Long teamId, String title, Long refId,
                                              List<Long> targets) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", "안내 텍스트");
        body.put("referenceVideoId", refId);
        body.put("targetUserIds", targets);
        return mvc.perform(post("/api/teams/{id}/missions", teamId).cookie(leader.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    public Long createMission(LoggedIn leader, Long teamId, String title, LoggedIn... targets) throws Exception {
        List<Long> ids = Arrays.stream(targets).map(LoggedIn::id).toList();
        return json(createMissionRequest(leader, teamId, title, missionReference(leader), ids)
                .andExpect(status().isCreated())).get("missionId").asLong();
    }

    public ResultActions submitRequest(LoggedIn user, Long missionId, Long videoId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("videoId", videoId);
        body.put("content", "제출합니다");
        return mvc.perform(post("/api/missions/{id}/submissions", missionId).cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    public Long submit(LoggedIn user, Long missionId) throws Exception {
        return json(submitRequest(user, missionId, missionSubmissionVideo(user))
                .andExpect(status().isCreated())).get("submissionId").asLong();
    }

    public ResultActions comment(LoggedIn user, Long submissionId, String content) throws Exception {
        return mvc.perform(post("/api/submissions/{id}/comments", submissionId).cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("content", content))));
    }

    public ResultActions deleteMission(LoggedIn user, Long missionId) throws Exception {
        return mvc.perform(delete("/api/missions/{id}", missionId).cookie(user.cookie()));
    }

    public JsonNode json(ResultActions result) throws Exception {
        return om.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
