package SeSAC.Dance_Assessment.Chat;

import SeSAC.Dance_Assessment.PostgresTestContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V4 마이그레이션 검증(11.2). 운영처럼 <b>팀·멤버가 이미 있는 V3 상태의 DB</b>에 V4를 적용한다.
 *
 * <p>다른 테스트와 섞이지 않게 같은 컨테이너에 빈 데이터베이스를 따로 만든다. Spring 컨텍스트를
 * 쓰지 않는다 — 앱이 아니라 마이그레이션 파일 자체를 본다.
 *
 * <p>옮겨 담기 확인이 먼저다. 제약 확인은 행을 더 넣으므로 그 뒤에 돈다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V4MigrationTest {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateV3ThenSeedThenV4() {
        // 컨테이너는 정적 초기화에서 시작된다(다른 테스트와 같은 컨테이너).
        String database = "v4check_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        new JdbcTemplate(dataSource(PostgresTestContainer.jdbcUrl())).execute("CREATE DATABASE " + database);
        DataSource ds = dataSource(PostgresTestContainer.jdbcUrl(database));
        jdbc = new JdbcTemplate(ds);

        Flyway.configure().dataSource(ds).target("3").load().migrate();
        seedV3();
        Flyway.configure().dataSource(ds).load().migrate();
    }

    /**
     * 팀 3개: 팀장+팀원 2(ACTIVE), 팀장+나간 사람(LEFT)+추방된 사람(KICKED), 팀장만.
     * 팀원 한 명은 두 팀에 속한다.
     */
    private static void seedV3() {
        long leader1 = user("leader1");
        long leader2 = user("leader2");
        long leader3 = user("leader3");
        long m1 = user("m1");
        long m2 = user("m2");
        long left = user("left");
        long kicked = user("kicked");

        long t1 = team("팀1", "AAAAAAA1");
        long t2 = team("팀2", "AAAAAAA2");
        long t3 = team("팀3", "AAAAAAA3");
        member(t1, leader1, "LEADER", "ACTIVE");
        member(t1, m1, "MEMBER", "ACTIVE");
        member(t1, m2, "MEMBER", "ACTIVE");
        member(t2, leader2, "LEADER", "ACTIVE");
        member(t2, m1, "MEMBER", "ACTIVE");
        member(t2, left, "MEMBER", "LEFT");
        member(t2, kicked, "MEMBER", "KICKED");
        member(t3, leader3, "LEADER", "ACTIVE");
    }

    @Test
    @Order(1)
    @DisplayName("V3 → V4: 팀마다 채팅방 1개, ACTIVE 팀 멤버마다 채팅 멤버 1개(나간·추방된 사람은 없음), 읽은 위치 0 = 방 last_seq")
    void backfill() {
        assertThat(count("SELECT count(*) FROM team")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM chat_room WHERE type = 'TEAM'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM team t WHERE NOT EXISTS"
                + " (SELECT 1 FROM chat_room r WHERE r.team_id = t.team_id)")).isZero();

        assertThat(count("SELECT count(*) FROM chat_room_member")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM team_member m JOIN chat_room r ON r.team_id = m.team_id"
                + " LEFT JOIN chat_room_member c ON c.room_id = r.room_id AND c.user_id = m.user_id"
                + " WHERE m.status = 'ACTIVE' AND c.member_id IS NULL"))
                .as("채팅 멤버가 빠진 ACTIVE 팀 멤버").isZero();
        assertThat(count("SELECT count(*) FROM team_member m JOIN chat_room r ON r.team_id = m.team_id"
                + " JOIN chat_room_member c ON c.room_id = r.room_id AND c.user_id = m.user_id"
                + " WHERE m.status <> 'ACTIVE'"))
                .as("나간·추방된 팀 멤버에게는 채팅 멤버를 만들지 않는다").isZero();
        assertThat(count("SELECT count(*) FROM chat_room_member c JOIN chat_room r ON r.room_id = c.room_id"
                + " WHERE c.status <> 'ACTIVE' OR c.last_read_seq <> 0 OR c.last_read_seq <> r.last_seq")).isZero();
        assertThat(count("SELECT count(*) FROM message")).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("Flyway 이력이 v4이고, 제약이 기대대로 막는다(DM 쌍 순서, 팀 방 1개, 부분 인덱스)")
    void constraints() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1");
        assertThat(history.get(0).get("version")).isEqualTo("4");

        long a = user("dm_a");
        long b = user("dm_b");
        long low = Math.min(a, b);
        long high = Math.max(a, b);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO chat_room (type, dm_user_low, dm_user_high) VALUES ('DM', ?, ?)", high, low))
                .hasMessageContaining("chat_room_shape_check");
        jdbc.update("INSERT INTO chat_room (type, dm_user_low, dm_user_high) VALUES ('DM', ?, ?)", low, high);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO chat_room (type, dm_user_low, dm_user_high) VALUES ('DM', ?, ?)", low, high))
                .hasMessageContaining("uk_chat_room_dm_pair");

        Long teamId = jdbc.queryForObject("SELECT team_id FROM team ORDER BY team_id LIMIT 1", Long.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chat_room (type, team_id) VALUES ('TEAM', ?)", teamId))
                .hasMessageContaining("uk_chat_room_team");

        Long room = jdbc.queryForObject("SELECT room_id FROM chat_room WHERE team_id = ?", Long.class, teamId);
        UUID event = UUID.randomUUID();
        jdbc.update("INSERT INTO message (room_id, seq, type, content, client_message_id, created_at)"
                + " VALUES (?, 1, 'SYSTEM', '{}', ?, now())", room, event);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO message (room_id, seq, type, content, client_message_id, created_at)"
                + " VALUES (?, 2, 'SYSTEM', '{}', ?, now())", room, event))
                .hasMessageContaining("uk_message_system_client");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO message (room_id, seq, type, content, client_message_id, created_at)"
                + " VALUES (?, 1, 'SYSTEM', '{}', ?, now())", room, UUID.randomUUID()))
                .hasMessageContaining("uk_message_room_seq");
    }

    private static long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static long user(String loginId) {
        return jdbc.queryForObject("INSERT INTO users (login_id, nickname, provider) VALUES (?, ?, 'LOCAL')"
                + " RETURNING user_id", Long.class, loginId, loginId);
    }

    private static long team(String name, String code) {
        return jdbc.queryForObject("INSERT INTO team (team_name, join_code, created_at, modified_at)"
                + " VALUES (?, ?, now(), now()) RETURNING team_id", Long.class, name, code);
    }

    private static void member(long teamId, long userId, String role, String status) {
        jdbc.update("INSERT INTO team_member (team_id, user_id, role, status, joined_at, created_at, modified_at)"
                + " VALUES (?, ?, ?, ?, now(), now(), now())", teamId, userId, role, status);
    }

    private static DataSource dataSource(String url) {
        return new DriverManagerDataSource(url, PostgresTestContainer.username(), PostgresTestContainer.password());
    }
}
