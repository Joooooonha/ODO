package SeSAC.Dance_Assessment.Mission;

import SeSAC.Dance_Assessment.Infrastructure.AnalysisResultRepository;
import SeSAC.Dance_Assessment.Infrastructure.MissionSubmissionRepository;
import SeSAC.Dance_Assessment.Infrastructure.PracticeLogRepository;
import SeSAC.Dance_Assessment.Service.AiClientService;
import SeSAC.Dance_Assessment.TestTeams;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mission (작업 지시서 7절 "브랜치 2 · Mission").
 *
 * <p>분석 서버 클라이언트는 목으로 바꿔, Mission 흐름이 <b>한 번도 부르지 않는지</b> 확인한다.
 * 트랜잭션을 테스트에 걸지 않는다 — 동시 제출 테스트는 각 요청이 실제로 커밋되어야 의미가 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class MissionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    PracticeLogRepository practiceLogRepository;

    @Autowired
    AnalysisResultRepository analysisResultRepository;

    @Autowired
    MissionSubmissionRepository submissionRepository;

    @MockitoBean
    AiClientService aiClientService;

    TestUsers users;
    TestTeams t;
    LoggedIn leader;
    LoggedIn target1;
    LoggedIn target2;
    LoggedIn bystander; // 팀원이지만 대상자가 아님
    LoggedIn stranger;  // 팀원이 아님
    Long teamId;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(mvc, om);
        t = new TestTeams(mvc, om);
        leader = users.create("팀장");
        target1 = users.create("대상1");
        target2 = users.create("대상2");
        bystander = users.create("구경꾼");
        stranger = users.create("외부인");
        teamId = t.teamOf(leader, target1, target2, bystander);
    }

    // ===== 생성·수정·삭제 권한 =====

    @Test
    @DisplayName("팀장이 아닌 팀원은 Mission을 생성·수정·삭제할 수 없다")
    void onlyLeaderManages() throws Exception {
        Long myRef = t.missionReference(target1);
        t.createMissionRequest(target1, teamId, "팀원의 미션", myRef, List.of(target2.id()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));

        Long missionId = t.createMission(leader, teamId, "미션", target1);
        mvc.perform(put("/api/missions/{id}", missionId).cookie(target1.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("title", "바꿈", "referenceVideoId", myRef))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
        t.deleteMission(target1, missionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
        t.deleteMission(stranger, missionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));
    }

    @Test
    @DisplayName("팀원이 아닌 사람·팀장 자신·나간 사람은 대상자가 될 수 없고, 대상자 0명·중복도 안 된다")
    void invalidTargets() throws Exception {
        LoggedIn leaver = users.create("나간팀원");
        t.rejoin(leaver, t.joinCode(leader, teamId));
        t.leave(leaver, teamId);
        Long ref = t.missionReference(leader);

        for (List<Long> targets : List.of(
                List.of(stranger.id()), List.of(leader.id()), List.of(leaver.id()), List.<Long>of(),
                List.of(target1.id(), target1.id()))) {
            t.createMissionRequest(leader, teamId, "미션", ref, targets).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/teams/{id}/missions", teamId).cookie(leader.cookie()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("레퍼런스 영상 없이(또는 쓸 수 없는 영상으로) 만들 수 없다")
    void referenceRequired() throws Exception {
        Long generalRef = t.upload(leader, "REFERENCE", null, null).get("videoId").asLong();
        Long missionPractice = t.missionSubmissionVideo(leader);
        Long othersRef = t.missionReference(target1);

        t.createMissionRequest(leader, teamId, "미션", null, List.of(target1.id())).andExpect(status().isBadRequest());
        for (Long bad : List.of(generalRef, missionPractice, othersRef)) {
            t.createMissionRequest(leader, teamId, "미션", bad, List.of(target1.id()))
                    .andExpect(status().isBadRequest());
        }
    }

    // ===== 조회 =====

    @Test
    @DisplayName("대상자가 아닌 팀원도 목록·상세·제출 현황·제출물·코멘트를 볼 수 있다")
    void bystanderCanView() throws Exception {
        Long missionId = t.createMission(leader, teamId, "보는 미션", target1, target2);
        Long submissionId = t.submit(target1, missionId);
        t.comment(leader, submissionId, "좋아요").andExpect(status().isCreated());

        mvc.perform(get("/api/teams/{id}/missions", teamId).cookie(bystander.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("보는 미션"))
                .andExpect(jsonPath("$[0].submittedCount").value(1))
                .andExpect(jsonPath("$[0].targetCount").value(2))
                .andExpect(jsonPath("$[0].myStatus").doesNotExist());
        mvc.perform(get("/api/missions/{id}", missionId).cookie(bystander.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets.length()").value(2))
                .andExpect(jsonPath("$.myStatus").doesNotExist());
        mvc.perform(get("/api/submissions/{id}", submissionId).cookie(bystander.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("제출합니다"))
                .andExpect(jsonPath("$.canComment").value(false))
                .andExpect(jsonPath("$.comments[0].content").value("좋아요"))
                .andExpect(jsonPath("$.comments[0].writerNickname").value("팀장"));

        // 대상자에게는 내 상태가 보인다
        mvc.perform(get("/api/teams/{id}/missions", teamId).cookie(target1.cookie()))
                .andExpect(jsonPath("$[0].myStatus").value("SUBMITTED"));
        mvc.perform(get("/api/teams/{id}/missions", teamId).cookie(target2.cookie()))
                .andExpect(jsonPath("$[0].myStatus").value("NOT_SUBMITTED"));
    }

    @Test
    @DisplayName("팀원이 아닌 사람(나가거나 추방된 사람 포함)은 Mission을 볼 수 없다")
    void outsidersCannotView() throws Exception {
        Long missionId = t.createMission(leader, teamId, "비공개", target1, target2);
        Long submissionId = t.submit(target1, missionId);
        t.leave(target2, teamId);
        t.kick(leader, teamId, bystander);

        for (LoggedIn outsider : List.of(stranger, target2, bystander)) {
            mvc.perform(get("/api/teams/{id}/missions", teamId).cookie(outsider.cookie()))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/missions/{id}", missionId).cookie(outsider.cookie()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));
            mvc.perform(get("/api/submissions/{id}", submissionId).cookie(outsider.cookie()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("제목·안내 텍스트·레퍼런스 영상 수정이 반영되고, 대상자 목록은 바뀌지 않는다")
    void updateKeepsTargets() throws Exception {
        Long missionId = t.createMission(leader, teamId, "처음 제목", target1);
        Long newRef = t.missionReference(leader);

        mvc.perform(put("/api/missions/{id}", missionId).cookie(leader.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "title", "바뀐 제목", "description", "바뀐 안내", "referenceVideoId", newRef,
                                // 대상자를 보내도 무시된다 (요청 형식에 없는 필드)
                                "targetUserIds", List.of(target2.id())))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/missions/{id}", missionId).cookie(bystander.cookie()))
                .andExpect(jsonPath("$.title").value("바뀐 제목"))
                .andExpect(jsonPath("$.description").value("바뀐 안내"))
                .andExpect(jsonPath("$.referenceVideoId").value(newRef))
                .andExpect(jsonPath("$.targets.length()").value(1))
                .andExpect(jsonPath("$.targets[0].userId").value(target1.id()));
        mvc.perform(get("/api/teams/{id}/missions", teamId).cookie(bystander.cookie()))
                .andExpect(jsonPath("$[0].title").value("바뀐 제목"));
    }

    // ===== 제출 =====

    @Test
    @DisplayName("대상자가 아닌 팀원, 나가거나 추방된 대상자는 제출할 수 없고, 영상 없이도 제출할 수 없다")
    void submitRestrictions() throws Exception {
        LoggedIn kicked = users.create("추방될대상");
        t.rejoin(kicked, t.joinCode(leader, teamId));
        Long missionId = t.createMission(leader, teamId, "제출 규칙", target1, target2, kicked);

        t.submitRequest(bystander, missionId, t.missionSubmissionVideo(bystander))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MISSION_TARGET"));

        Long target2Video = t.missionSubmissionVideo(target2);
        t.leave(target2, teamId);
        t.submitRequest(target2, missionId, target2Video)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));

        Long kickedVideo = t.missionSubmissionVideo(kicked);
        t.kick(leader, teamId, kicked);
        t.submitRequest(kicked, missionId, kickedVideo)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));

        t.submitRequest(target1, missionId, null).andExpect(status().isBadRequest());
        Long generalVideo = t.upload(target1, "PRACTICE", null, null).get("videoId").asLong();
        t.submitRequest(target1, missionId, generalVideo).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("나갔다가 다시 참여한 대상자는 다시 제출할 수 있다 (D16)")
    void rejoinedTargetCanSubmit() throws Exception {
        Long missionId = t.createMission(leader, teamId, "재참여", target1);
        t.leave(target1, teamId);
        t.rejoin(target1, t.joinCode(leader, teamId));
        t.submit(target1, missionId);
    }

    @Test
    @DisplayName("[동시] 같은 제출이 동시에 와도 1건만 저장된다")
    void concurrentSubmit() throws Exception {
        Long missionId = t.createMission(leader, teamId, "동시 제출", target1);
        Long video = t.missionSubmissionVideo(target1);

        List<MockHttpServletResponse> results = concurrently(10,
                () -> t.submitRequest(target1, missionId, video).andReturn().getResponse());

        assertThat(results).extracting(MockHttpServletResponse::getStatus).containsOnly(201, 409);
        assertThat(results.stream().filter(r -> r.getStatus() == 201)).hasSize(1);
        for (MockHttpServletResponse r : results) {
            if (r.getStatus() == 409) {
                assertThat(om.readTree(r.getContentAsString()).get("code").asText()).isEqualTo("ALREADY_SUBMITTED");
            }
        }
        long rows = submissionRepository.findByMission(missionId).size();
        assertThat(rows).isEqualTo(1);

        t.submitRequest(target1, missionId, video)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SUBMITTED"));
    }

    @Test
    @DisplayName("[핵심] Mission 영상 업로드·생성·제출은 연습 기록·분석 결과를 만들지 않고 분석 서버를 부르지 않는다")
    void missionNeverTouchesAnalysis() throws Exception {
        long logs = practiceLogRepository.count();
        long results = analysisResultRepository.count();

        Long missionId = t.createMission(leader, teamId, "분석 없음", target1);
        JsonNode done = t.upload(target1, "PRACTICE", "MISSION", null);
        assertThat(done.get("logId").isNull()).isTrue();
        assertThat(done.get("analyzable").asBoolean()).isFalse();
        t.submitRequest(target1, missionId, done.get("videoId").asLong()).andExpect(status().isCreated());

        assertThat(practiceLogRepository.count()).isEqualTo(logs);
        assertThat(analysisResultRepository.count()).isEqualTo(results);
        verifyNoInteractions(aiClientService);
    }

    @Test
    @DisplayName("Mission 영상은 '다시 쓸 수 있는 기준 영상' 목록에 나오지 않고, 자유 연습의 기준으로도 쓸 수 없다")
    void missionVideosStayOutOfFreePractice() throws Exception {
        Long general = t.upload(leader, "REFERENCE", null, null).get("videoId").asLong();
        Long mission = t.missionReference(leader);

        String body = mvc.perform(get("/api/videos").param("type", "REFERENCE").cookie(leader.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> ids = new ArrayList<>();
        om.readTree(body).forEach(n -> ids.add(n.get("videoId").asLong()));
        assertThat(ids).contains(general).doesNotContain(mission);

        // 자유 연습 업로드에서 Mission 영상을 기준으로 걸면 거부된다(분석 서버로 넘어가므로)
        String issued = mvc.perform(post("/api/videos/upload-url").cookie(leader.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "filename", "p.mp4", "contentType", "video/mp4", "type", "PRACTICE"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long practice = om.readTree(issued).get("videoId").asLong();
        long logsBefore = practiceLogRepository.count();
        mvc.perform(post("/api/videos/{id}/complete", practice).cookie(leader.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("referenceVideoId", mission))))
                .andExpect(status().isBadRequest());
        assertThat(practiceLogRepository.count()).isEqualTo(logsBefore);
    }

    // ===== 재생 권한 =====

    @Test
    @DisplayName("같은 팀 활동 중 멤버는 레퍼런스·다른 팀원의 제출 영상을 재생할 수 있고, 팀원이 아니면 못 한다")
    void missionVideoPlayback() throws Exception {
        Long missionId = t.createMission(leader, teamId, "재생", target1, target2);
        JsonNode detail = t.json(mvc.perform(get("/api/missions/{id}", missionId).cookie(leader.cookie())));
        long ref = detail.get("referenceVideoId").asLong();
        Long subVideo = t.missionSubmissionVideo(target1);
        t.submitRequest(target1, missionId, subVideo).andExpect(status().isCreated());

        for (LoggedIn member : List.of(bystander, target2, leader)) {
            play(member, ref).andExpect(status().isFound());
            play(member, subVideo).andExpect(status().isFound());
        }
        play(stranger, ref).andExpect(status().isForbidden());
        play(stranger, subVideo).andExpect(status().isForbidden());

        // 나간 사람: 남의 영상은 못 보고, 자기가 올린 Mission 영상은 본인 영상 규칙으로 볼 수 있다 (D16)
        t.leave(target1, teamId);
        play(target1, ref).andExpect(status().isForbidden());
        play(target1, subVideo).andExpect(status().isFound());
        // 나간 대상자의 제출물은 남은 멤버가 계속 볼 수 있다
        play(bystander, subVideo).andExpect(status().isFound());

        // 미션이 삭제되면 팀원도 더 이상 볼 수 없다 (업로더 본인은 예외)
        t.deleteMission(leader, missionId).andExpect(status().isNoContent());
        play(bystander, ref).andExpect(status().isForbidden());
        play(bystander, subVideo).andExpect(status().isForbidden());
        play(leader, ref).andExpect(status().isFound());
    }

    // ===== 제출 현황·코멘트·삭제 =====

    @Test
    @DisplayName("제출 현황이 실제 제출 데이터와 일치한다 (나간 대상자 포함)")
    void statusMatchesSubmissions() throws Exception {
        Long missionId = t.createMission(leader, teamId, "현황", target1, target2, bystander);
        Long s1 = t.submit(target1, missionId);
        t.leave(target1, teamId);   // 제출하고 나감
        t.leave(target2, teamId);   // 미제출로 나감

        JsonNode detail = t.json(mvc.perform(get("/api/missions/{id}", missionId).cookie(leader.cookie())));
        assertThat(detail.get("targets")).hasSize(3);
        for (JsonNode target : detail.get("targets")) {
            long uid = target.get("userId").asLong();
            if (uid == target1.id()) {
                assertThat(target.get("submissionId").asLong()).isEqualTo(s1);
            } else {
                assertThat(target.get("submissionId").isNull()).isTrue();
            }
        }
        mvc.perform(get("/api/teams/{id}/missions", teamId).cookie(leader.cookie()))
                .andExpect(jsonPath("$[0].submittedCount").value(1))
                .andExpect(jsonPath("$[0].targetCount").value(3));
    }

    @Test
    @DisplayName("코멘트는 팀장과 그 제출자만 쓸 수 있다")
    void commentPermissions() throws Exception {
        Long missionId = t.createMission(leader, teamId, "코멘트", target1, target2);
        Long submissionId = t.submit(target1, missionId);

        t.comment(leader, submissionId, "팀장 코멘트").andExpect(status().isCreated());
        t.comment(target1, submissionId, "제출자 답글").andExpect(status().isCreated());
        t.comment(target2, submissionId, "다른 대상자").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_ALLOWED"));
        t.comment(bystander, submissionId, "구경꾼").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_ALLOWED"));
        t.comment(stranger, submissionId, "외부인").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));
        t.comment(leader, submissionId, "   ").andExpect(status().isBadRequest());

        mvc.perform(get("/api/submissions/{id}", submissionId).cookie(target1.cookie()))
                .andExpect(jsonPath("$.canComment").value(true))
                .andExpect(jsonPath("$.comments.length()").value(2))
                .andExpect(jsonPath("$.comments[0].content").value("팀장 코멘트"))
                .andExpect(jsonPath("$.comments[1].writerNickname").value("대상1"));
    }

    @Test
    @DisplayName("삭제된 Mission은 목록에서 빠지고, 상세·제출물·코멘트가 404 MISSION_DELETED로 거부된다")
    void deletedMission() throws Exception {
        Long missionId = t.createMission(leader, teamId, "지울 미션", target1);
        Long submissionId = t.submit(target1, missionId);
        t.deleteMission(leader, missionId).andExpect(status().isNoContent());

        mvc.perform(get("/api/teams/{id}/missions", teamId).cookie(bystander.cookie()))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/missions/{id}", missionId).cookie(bystander.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MISSION_DELETED"));
        mvc.perform(get("/api/submissions/{id}", submissionId).cookie(target1.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MISSION_DELETED"));
        t.comment(leader, submissionId, "늦은 코멘트").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MISSION_DELETED"));
        t.deleteMission(leader, missionId).andExpect(status().isNotFound());
        // 팀 밖 사람에게는 삭제 여부도 알려주지 않는다
        mvc.perform(get("/api/missions/{id}", missionId).cookie(stranger.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/missions/{id}", Long.MAX_VALUE).cookie(leader.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MISSION_NOT_FOUND"));
    }

    // ===== 도우미 =====

    private ResultActions play(LoggedIn viewer, long videoId) throws Exception {
        return mvc.perform(get("/api/videos/{id}", videoId).cookie(viewer.cookie()));
    }

    private List<MockHttpServletResponse> concurrently(int n, Callable<MockHttpServletResponse> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<MockHttpServletResponse> out = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
