package SeSAC.Dance_Assessment.Mission;

import SeSAC.Dance_Assessment.TestTeams;
import SeSAC.Dance_Assessment.TestUsers;
import SeSAC.Dance_Assessment.TestUsers.LoggedIn;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 미션 목록 API가 미션 수와 상관없이 같은 개수의 SQL만 실행하는지 (N+1 방지, D16).
 *
 * <p>제출 수·대상자 수·내 상태는 집계 쿼리 한 번으로 구한다. 미션마다 세면 미션 수만큼 SQL이 는다.
 * Hibernate 통계로 이 API 한 번 호출에 준비된 SQL 문 개수를 센다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MissionListQueryCountTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    EntityManagerFactory emf;

    @Test
    @DisplayName("미션 목록의 SQL 개수는 미션이 1개일 때와 6개일 때 같다")
    void listQueryCountIsConstant() throws Exception {
        TestUsers users = new TestUsers(mvc, om);
        TestTeams t = new TestTeams(mvc, om);
        LoggedIn leader = users.create("집계팀장");
        LoggedIn a = users.create("집계A");
        LoggedIn b = users.create("집계B");
        Long teamId = t.teamOf(leader, a, b);

        Long first = t.createMission(leader, teamId, "미션 0", a, b);
        t.submit(a, first);
        long withOne = countListQueries(a, teamId, 1);

        for (int i = 1; i < 6; i++) {
            Long id = t.createMission(leader, teamId, "미션 " + i, a, b);
            if (i % 2 == 0) {
                t.submit(b, id);
            }
        }
        long withSix = countListQueries(a, teamId, 6);

        System.out.printf("[미션 목록 SQL] 미션 1개: %d개, 미션 6개: %d개%n", withOne, withSix);
        assertThat(withSix).isEqualTo(withOne);
        assertThat(withOne).isLessThanOrEqualTo(4); // 팀 확인 + 멤버 확인 + 미션 목록 + 집계
    }

    private long countListQueries(LoggedIn viewer, Long teamId, int expectedMissions) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/api/teams/{id}/missions", teamId).cookie(viewer.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(expectedMissions));
        return stats.getPrepareStatementCount();
    }
}
