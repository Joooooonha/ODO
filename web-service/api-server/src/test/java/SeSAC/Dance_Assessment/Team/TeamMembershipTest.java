package SeSAC.Dance_Assessment.Team;

import SeSAC.Dance_Assessment.Domain.TeamMember;
import SeSAC.Dance_Assessment.Domain.TeamMemberStatus;
import SeSAC.Dance_Assessment.Infrastructure.TeamMemberRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 팀 생성·코드 참여·나가기·추방 (작업 지시서 7절 "브랜치 1 · 팀").
 *
 * <p>트랜잭션을 테스트에 걸지 않는다. 동시 요청 테스트는 각 요청이 실제로 커밋되어야
 * 의미가 있다. 사용자·팀은 테스트마다 새로 만들어 서로 섞이지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class TeamMembershipTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TeamMemberRepository teamMemberRepository;

    TestUsers users;

    @BeforeEach
    void setUp() {
        users = new TestUsers(mvc, objectMapper);
    }

    // ===== 생성·목록·조회 =====

    @Test
    @DisplayName("팀을 만들면 만든 사람이 팀장이 되고, 팀마다 다른 참여 코드가 발급된다")
    void createTeamMakesLeaderAndUniqueCode() throws Exception {
        LoggedIn leader = users.create("팀장");

        mvc.perform(post("/api/teams").cookie(leader.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  첫 팀  "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("첫 팀"))
                .andExpect(jsonPath("$.myRole").value("LEADER"))
                .andExpect(jsonPath("$.memberCount").value(1));

        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            Long teamId = createTeam(leader, "팀" + i);
            String code = joinCode(leader, teamId);
            assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}");
            codes.add(code);
        }
        assertThat(codes).as("팀마다 참여 코드가 달라야 한다").hasSize(5);
    }

    @Test
    @DisplayName("팀 이름이 비었거나 30자를 넘으면 400")
    void invalidTeamName() throws Exception {
        LoggedIn user = users.create("사용자");
        mvc.perform(post("/api/teams").cookie(user.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "   "))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/teams").cookie(user.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "가".repeat(31)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("한 사용자가 여러 팀에 속하고 여러 팀의 팀장일 수 있으며, 내 팀 목록에는 활동 중인 팀만 나온다")
    void multipleTeamsAndActiveOnlyList() throws Exception {
        LoggedIn me = users.create("나");
        LoggedIn other = users.create("남");

        Long mine1 = createTeam(me, "내 팀 1");
        Long mine2 = createTeam(me, "내 팀 2");
        Long joined = createTeam(other, "남의 팀");
        Long left = createTeam(other, "나간 팀");
        Long notMine = createTeam(other, "모르는 팀");

        join(me, joinCode(other, joined)).andExpect(status().isOk());
        join(me, joinCode(other, left)).andExpect(status().isOk());
        mvc.perform(post("/api/teams/{id}/leave", left).cookie(me.cookie()))
                .andExpect(status().isNoContent());

        String body = mvc.perform(get("/api/teams").cookie(me.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = objectMapper.readTree(body);

        List<Long> ids = new ArrayList<>();
        list.forEach(n -> ids.add(n.get("teamId").asLong()));
        assertThat(ids).containsExactlyInAnyOrder(mine1, mine2, joined)
                .doesNotContain(left, notMine);

        list.forEach(n -> {
            long id = n.get("teamId").asLong();
            String expectedRole = id == joined ? "MEMBER" : "LEADER";
            assertThat(n.get("myRole").asText()).isEqualTo(expectedRole);
            assertThat(n.get("memberCount").asLong()).isEqualTo(id == joined ? 2 : 1);
            assertThat(n.has("joinCode")).as("목록에 참여 코드를 싣지 않는다").isFalse();
        });
    }

    @Test
    @DisplayName("참여 코드는 팀장만 볼 수 있다")
    void joinCodeIsLeaderOnly() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        LoggedIn stranger = users.create("외부인");
        Long teamId = createTeam(leader, "코드 팀");
        join(member, joinCode(leader, teamId)).andExpect(status().isOk());

        mvc.perform(get("/api/teams/{id}/join-code", teamId).cookie(member.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
        mvc.perform(get("/api/teams/{id}/join-code", teamId).cookie(stranger.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));
        mvc.perform(get("/api/teams/{id}", teamId).cookie(member.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinCode").doesNotExist());
    }

    // ===== 참여 =====

    @Test
    @DisplayName("코드로 팀 이름·팀장 닉네임·인원 수를 확인한 뒤 참여할 수 있다")
    void previewThenJoin() throws Exception {
        LoggedIn leader = users.create("확인팀장");
        LoggedIn member = users.create("새팀원");
        Long teamId = createTeam(leader, "확인 팀");
        String code = joinCode(leader, teamId);

        // 소문자·하이픈·공백이 섞여도 같은 코드로 본다
        String typed = " " + code.substring(0, 4).toLowerCase() + "-" + code.substring(4) + " ";
        mvc.perform(post("/api/teams/join-preview").cookie(member.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", typed))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("확인 팀"))
                .andExpect(jsonPath("$.leaderNickname").value("확인팀장"))
                .andExpect(jsonPath("$.memberCount").value(1));

        join(member, typed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(teamId))
                .andExpect(jsonPath("$.myRole").value("MEMBER"))
                .andExpect(jsonPath("$.memberCount").value(2));

        mvc.perform(get("/api/teams/{id}/members", teamId).cookie(member.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nickname").value("확인팀장"))
                .andExpect(jsonPath("$[0].role").value("LEADER"))
                .andExpect(jsonPath("$[1].nickname").value("새팀원"))
                .andExpect(jsonPath("$[1].role").value("MEMBER"));
    }

    @Test
    @DisplayName("존재하지 않는 코드로는 확인도 참여도 할 수 없다")
    void unknownCode() throws Exception {
        LoggedIn user = users.create("사용자");
        for (String code : List.of("ZZZZZZZZ", "short", "")) {
            mvc.perform(post("/api/teams/join-preview").cookie(user.cookie())
                            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", code))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("JOIN_CODE_NOT_FOUND"));
            join(user, code)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("JOIN_CODE_NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("활동 중인 팀에 다시 참여할 수 없다 (팀장 포함)")
    void cannotJoinTwice() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원");
        Long teamId = createTeam(leader, "중복 팀");
        String code = joinCode(leader, teamId);

        join(member, code).andExpect(status().isOk());
        join(member, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
        join(leader, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
        assertThat(records(teamId, member.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("[동시] 같은 사람의 같은 팀 참여가 동시에 와도 성공은 1건, 멤버 기록도 1건")
    void concurrentFirstJoin() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("동시참여");
        Long teamId = createTeam(leader, "동시 팀");
        String code = joinCode(leader, teamId);

        List<MockHttpServletResponse> results = concurrently(10, () -> joinRequest(member, code));

        assertThat(statuses(results)).containsOnly(200, 409);
        assertThat(count(results, 200)).isEqualTo(1);
        results.stream().filter(r -> r.getStatus() == 409)
                .forEach(r -> assertThat(code(r)).isEqualTo("ALREADY_MEMBER"));
        assertThat(records(teamId, member.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("스스로 나간 사람은 다시 참여할 수 있고, 새 기록 없이 기존 기록이 다시 활성화된다")
    void leftMemberRejoinsSameRecord() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("돌아온 팀원");
        Long teamId = createTeam(leader, "재가입 팀");
        String code = joinCode(leader, teamId);

        join(member, code).andExpect(status().isOk());
        Long recordId = record(teamId, member.id()).getId();

        mvc.perform(post("/api/teams/{id}/leave", teamId).cookie(member.cookie()))
                .andExpect(status().isNoContent());
        TeamMember left = record(teamId, member.id());
        assertThat(left.getStatus()).isEqualTo(TeamMemberStatus.LEFT);
        assertThat(left.getLeftAt()).isNotNull();

        join(member, code).andExpect(status().isOk());
        TeamMember back = record(teamId, member.id());
        assertThat(back.getId()).as("새 기록이 아니라 같은 기록").isEqualTo(recordId);
        assertThat(back.getStatus()).isEqualTo(TeamMemberStatus.ACTIVE);
        assertThat(back.getLeftAt()).isNull();
        assertThat(records(teamId, member.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("[동시] 나간 사람의 재참여가 동시에 와도 성공은 1건, 기록은 1건")
    void concurrentRejoin() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("동시재가입");
        Long teamId = createTeam(leader, "동시 재가입 팀");
        String code = joinCode(leader, teamId);
        join(member, code).andExpect(status().isOk());
        mvc.perform(post("/api/teams/{id}/leave", teamId).cookie(member.cookie()))
                .andExpect(status().isNoContent());

        List<MockHttpServletResponse> results = concurrently(10, () -> joinRequest(member, code));

        assertThat(statuses(results)).containsOnly(200, 409);
        assertThat(count(results, 200)).isEqualTo(1);
        assertThat(records(teamId, member.id())).isEqualTo(1);
        assertThat(record(teamId, member.id()).getStatus()).isEqualTo(TeamMemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("추방된 사람은 같은 코드로 다시 참여할 수 없다 (403 KICKED_FROM_TEAM)")
    void kickedCannotRejoin() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("추방될 팀원");
        Long teamId = createTeam(leader, "추방 팀");
        String code = joinCode(leader, teamId);
        join(member, code).andExpect(status().isOk());

        kick(leader, teamId, member.id()).andExpect(status().isNoContent());
        assertThat(record(teamId, member.id()).getStatus()).isEqualTo(TeamMemberStatus.KICKED);

        join(member, code).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("KICKED_FROM_TEAM"));
        assertThat(record(teamId, member.id()).getStatus()).isEqualTo(TeamMemberStatus.KICKED);
    }

    // ===== 나가기·추방 =====

    @Test
    @DisplayName("팀장은 나갈 수 없고 자기 자신을 추방할 수 없다. 팀원은 추방할 수 없다")
    void leaderAndMemberRestrictions() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn member = users.create("팀원1");
        LoggedIn member2 = users.create("팀원2");
        LoggedIn stranger = users.create("외부인");
        Long teamId = createTeam(leader, "규칙 팀");
        String code = joinCode(leader, teamId);
        join(member, code).andExpect(status().isOk());
        join(member2, code).andExpect(status().isOk());

        mvc.perform(post("/api/teams/{id}/leave", teamId).cookie(leader.cookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEADER_CANNOT_LEAVE"));
        kick(leader, teamId, leader.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_KICK_LEADER"));
        kick(member, teamId, member2.id()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
        kick(member, teamId, leader.id()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
        kick(leader, teamId, stranger.id()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        mvc.perform(post("/api/teams/{id}/leave", teamId).cookie(stranger.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));

        assertThat(record(teamId, leader.id()).getStatus()).isEqualTo(TeamMemberStatus.ACTIVE);
        assertThat(record(teamId, member2.id()).getStatus()).isEqualTo(TeamMemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("나가거나 추방된 사람은 Team Space API에 즉시 접근할 수 없다")
    void leftOrKickedLoseAccess() throws Exception {
        LoggedIn leader = users.create("팀장");
        LoggedIn leaver = users.create("나간 사람");
        LoggedIn kicked = users.create("추방된 사람");
        Long teamId = createTeam(leader, "접근 팀");
        String code = joinCode(leader, teamId);
        join(leaver, code).andExpect(status().isOk());
        join(kicked, code).andExpect(status().isOk());

        mvc.perform(post("/api/teams/{id}/leave", teamId).cookie(leaver.cookie()))
                .andExpect(status().isNoContent());
        kick(leader, teamId, kicked.id()).andExpect(status().isNoContent());

        for (LoggedIn gone : List.of(leaver, kicked)) {
            mvc.perform(get("/api/teams/{id}", teamId).cookie(gone.cookie()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));
            mvc.perform(get("/api/teams/{id}/members", teamId).cookie(gone.cookie()))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/teams/{id}/leave", teamId).cookie(gone.cookie()))
                    .andExpect(status().isForbidden());
        }

        // 남은 멤버 목록과 인원 수에서도 빠진다
        mvc.perform(get("/api/teams/{id}/members", teamId).cookie(leader.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/teams/{id}", teamId).cookie(leader.cookie()))
                .andExpect(jsonPath("$.memberCount").value(1));
    }

    @Test
    @DisplayName("없는 팀은 404 TEAM_NOT_FOUND")
    void unknownTeam() throws Exception {
        LoggedIn user = users.create("사용자");
        mvc.perform(get("/api/teams/{id}", Long.MAX_VALUE).cookie(user.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
    }

    /**
     * 나가기와 추방이 같은 행을 동시에 바꾸는 경우.
     *
     * <p>행 잠금 없이 읽으면 둘 다 ACTIVE를 보고 통과해, 나중에 커밋한 쪽이 앞의 결과를
     * 덮어쓴다. LEFT가 KICKED를 덮으면 추방된 사람이 다시 참여할 수 있게 된다.
     * 잠그면 나중 요청은 앞 요청이 커밋한 상태를 보고 거부된다.
     *
     * <p>어느 쪽이 먼저 잠그는지는 실행마다 다르므로 여러 번 돌리고, 결과가 항상
     * "먼저 커밋한 쪽의 상태 + 나중 요청은 그에 맞는 거부"인지 확인한다.
     * 추방은 팀장 확인 쿼리를 먼저 하느라 출발이 같으면 늘 나가기가 먼저 잠근다.
     * 그래서 라운드마다 나가기 출발을 0~15ms 늦춰 두 순서가 모두 나오게 한다.
     */
    @Test
    @DisplayName("[동시] 나가기와 추방이 동시에 와도 갱신이 분실되지 않는다")
    void concurrentLeaveAndKick() throws Exception {
        LoggedIn leader = users.create("팀장");
        Long teamId = createTeam(leader, "경합 팀");
        String code = joinCode(leader, teamId);

        int leaveWon = 0;
        int kickWon = 0;
        for (int round = 0; round < 16; round++) {
            LoggedIn member = users.create("경합" + round);
            join(member, code).andExpect(status().isOk());
            long leaveDelayMs = round % 4 * 5L;

            List<MockHttpServletResponse> results = concurrently(List.of(
                    () -> {
                        Thread.sleep(leaveDelayMs);
                        return perform(post("/api/teams/{id}/leave", teamId).cookie(member.cookie()));
                    },
                    () -> perform(post("/api/teams/{id}/members/{uid}/kick", teamId, member.id())
                            .cookie(leader.cookie()))));
            MockHttpServletResponse leave = results.get(0);
            MockHttpServletResponse kick = results.get(1);
            TeamMemberStatus finalStatus = record(teamId, member.id()).getStatus();

            assertThat(count(results, 204)).as("성공은 정확히 하나").isEqualTo(1);
            if (leave.getStatus() == 204) {
                leaveWon++;
                assertThat(finalStatus).isEqualTo(TeamMemberStatus.LEFT);
                assertThat(kick.getStatus()).isEqualTo(404);
                assertThat(code(kick)).isEqualTo("MEMBER_NOT_FOUND");
            } else {
                kickWon++;
                assertThat(finalStatus).isEqualTo(TeamMemberStatus.KICKED);
                assertThat(leave.getStatus()).isEqualTo(403);
                assertThat(code(leave)).isEqualTo("NOT_TEAM_MEMBER");
                // 추방이 이겼다면 그 사람은 다시 들어올 수 없어야 한다
                join(member, code).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("KICKED_FROM_TEAM"));
            }
        }
        System.out.printf("[concurrentLeaveAndKick] leave 먼저=%d, kick 먼저=%d%n", leaveWon, kickWon);
        assertThat(leaveWon).as("나가기가 먼저 커밋한 경우도 확인했어야 한다").isPositive();
        assertThat(kickWon).as("추방이 먼저 커밋한 경우도 확인했어야 한다").isPositive();
    }

    // ===== 도우미 =====

    private Long createTeam(LoggedIn user, String name) throws Exception {
        String body = mvc.perform(post("/api/teams").cookie(user.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("teamId").asLong();
    }

    private String joinCode(LoggedIn leader, Long teamId) throws Exception {
        String body = mvc.perform(get("/api/teams/{id}/join-code", teamId).cookie(leader.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("joinCode").asText();
    }

    private org.springframework.test.web.servlet.ResultActions join(LoggedIn user, String code) throws Exception {
        return mvc.perform(post("/api/teams/join").cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", code))));
    }

    private MockHttpServletResponse joinRequest(LoggedIn user, String code) throws Exception {
        return perform(post("/api/teams/join").cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", code))));
    }

    private org.springframework.test.web.servlet.ResultActions kick(LoggedIn leader, Long teamId, Long target)
            throws Exception {
        return mvc.perform(post("/api/teams/{id}/members/{uid}/kick", teamId, target).cookie(leader.cookie()));
    }

    private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private TeamMember record(Long teamId, Long userId) {
        return teamMemberRepository.findByTeamIdAndUserId(teamId, userId).orElseThrow();
    }

    private long records(Long teamId, Long userId) {
        return teamMemberRepository.findAll().stream()
                .filter(m -> m.getTeam().getId().equals(teamId) && m.getUser().getId().equals(userId))
                .count();
    }

    private List<MockHttpServletResponse> concurrently(int n, Callable<MockHttpServletResponse> task)
            throws Exception {
        List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(task);
        }
        return concurrently(tasks);
    }

    /** 모든 작업을 같은 순간에 출발시킨다. 결과는 작업 순서대로. */
    private List<MockHttpServletResponse> concurrently(List<Callable<MockHttpServletResponse>> tasks)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (Callable<MockHttpServletResponse> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<MockHttpServletResponse> results = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> statuses(List<MockHttpServletResponse> results) {
        return results.stream().map(MockHttpServletResponse::getStatus).toList();
    }

    private static long count(List<MockHttpServletResponse> results, int status) {
        return results.stream().filter(r -> r.getStatus() == status).count();
    }

    private String code(MockHttpServletResponse response) {
        try {
            return objectMapper.readTree(response.getContentAsString()).path("code").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
