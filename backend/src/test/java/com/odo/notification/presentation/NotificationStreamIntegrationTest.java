package com.odo.notification.presentation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.odo.OdoApplication;
import com.odo.TestcontainersConfiguration;
import com.odo.auth.application.AccessTokenIssuer;
import com.odo.notification.application.NotificationDraft;
import com.odo.notification.application.NotificationWriter;
import com.odo.notification.application.stream.NotificationStreamRegistry;
import com.odo.notification.domain.repository.NotificationInboxRepository;
import com.odo.support.DatabaseCleaner;
import com.odo.support.MutableClock;
import com.odo.support.SseClient;
import com.odo.support.SseClient.Event;
import com.odo.support.TestAccounts;
import com.odo.support.TestClockConfiguration;
import com.odo.support.TestStorageConfiguration;
import com.odo.support.TestTeams;
import com.odo.team.application.TeamService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 실시간 연결(SSE)과 서버 사이 전달(DB 폴링). 11_Notification_spec.md의 "실시간 (통합)", 설계의 "서버 사이 전달"과 "연결: SSE".
 * 이 서버(서버 1)를 실제 포트로 띄우고, 같은 PostgreSQL 컨테이너를 쓰는 서버 2를 다른 포트로 띄워 서버가 여러 대인 경우를 재현한다.
 * 감지 주기 100ms, 빈 신호 1초, 따라잡기 상한 3으로 줄여 기다리는 시간을 짧게 한다. 기다리는 시간은 넉넉히 2초다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"odo.notification.detector-enabled=true", "odo.notification.poll-interval=100ms",
		"odo.notification.heartbeat-interval=1s", "odo.notification.catch-up-limit=3" })
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class, TestStorageConfiguration.class })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationStreamIntegrationTest {

	private static final Duration WAIT = Duration.ofSeconds(2);

	private static final String[] STREAM_PROPERTIES = { "odo.notification.detector-enabled=true",
			"odo.notification.poll-interval=100ms", "odo.notification.heartbeat-interval=1s",
			"odo.notification.catch-up-limit=3" };

	@LocalServerPort
	int port;

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	NotificationWriter writer;

	@Autowired
	NotificationStreamRegistry registry;

	@MockitoSpyBean
	NotificationInboxRepository inboxRepository;

	@Autowired
	TeamService teamService;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	/** 같은 DB를 쓰는 다른 서버. */
	private ConfigurableApplicationContext server2;

	private long leader;

	private long memberA;

	private long memberB;

	private long teamId;

	@BeforeAll
	void startServer2() {
		server2 = startServer();
	}

	@AfterAll
	void stopServer2() {
		if (server2 != null) {
			server2.close();
		}
	}

	@BeforeEach
	void setUp() {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 1000");
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		memberA = TestAccounts.create(jdbc, "김지수", clock.instant());
		memberB = TestAccounts.create(jdbc, "이서연", clock.instant());
		teamId = teamService.createTeam(leader, "블랙홀", null, null).id();
		TestTeams.addMember(jdbc, teamId, memberA, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, memberB, 26, clock.instant());
	}

	/**
	 * 같은 PostgreSQL 컨테이너를 쓰는 서버를 하나 더 띄운다. 감지와 연결 설정은 이 서버와 같다.
	 * 설정은 명령줄 인자로 넘긴다. SpringApplicationBuilder.properties는 가장 낮은 기본값이라 application-test.yaml
	 * (detector-enabled: false)에 진다(PR B 구현 중 승인받아 고침).
	 */
	private ConfigurableApplicationContext startServer() {
		List<String> properties = new ArrayList<>(List.of(STREAM_PROPERTIES));
		properties.add("server.port=0");
		properties.add("spring.datasource.url=" + postgres.getJdbcUrl());
		properties.add("spring.datasource.username=" + postgres.getUsername());
		properties.add("spring.datasource.password=" + postgres.getPassword());
		return new SpringApplicationBuilder(OdoApplication.class, TestClockConfiguration.class,
				TestStorageConfiguration.class)
			.profiles("test")
			.run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
	}

	private static String baseUrlOf(ConfigurableApplicationContext context) {
		return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
	}

	private String server1() {
		return "http://localhost:" + port;
	}

	private String bearer(long accountId) {
		return "Bearer " + accessTokenIssuer.issue(accountId);
	}

	/** recipients에게 알림 하나(일정 변경)를 남긴다. 팀장이 한 일이다. */
	private void notifyMembers(Collection<Long> recipients) {
		transactionTemplate.executeWithoutResult(status -> writer.add(new NotificationDraft(teamId,
				"SCHEDULE_UPDATED", "SCHEDULE", 77L, leader, recipients, Map.of())));
	}

	private void notifyA(int times) {
		for (int i = 0; i < times; i++) {
			notifyMembers(List.of(memberA));
		}
	}

	/** 연결을 열고 첫 이벤트(다시 연결 간격)를 받는다. 이 이벤트가 오면 등록이 끝난 것이다. */
	private SseClient open(String baseUrl, long accountId, String lastEventId) throws Exception {
		SseClient client = SseClient.connect(baseUrl, bearer(accountId), lastEventId);
		assertThat(client.status()).isEqualTo(200);
		Event first = client.next(WAIT);
		assertThat(first).as("첫 이벤트").isNotNull();
		assertThat(first.retry()).isEqualTo("3000");
		return client;
	}

	/** 알림 이벤트의 순번들을 받은 순서대로. expected개를 받거나 시간이 다 되면 돌아온다. */
	private static List<String> receiveIds(SseClient client, int expected) throws InterruptedException {
		List<String> ids = new ArrayList<>();
		while (ids.size() < expected) {
			Event event = client.next(WAIT);
			if (event == null) {
				break;
			}
			assertThat(event.event()).isEqualTo("notification");
			ids.add(event.id());
		}
		return ids;
	}

	@Nested
	@DisplayName("전달")
	class Delivery {

		@Test
		@DisplayName("연결하면 retry: 3000이 먼저 오고, 알림이 생기면 id가 순번이고 data가 목록의 항목과 같은 이벤트가 온다")
		void deliversNotification() throws Exception {
			try (SseClient client = open(server1(), memberA, "0")) {
				notifyA(1);

				Event event = client.next(WAIT);
				assertThat(event).isNotNull();
				assertThat(event.event()).isEqualTo("notification");
				assertThat(event.id()).isEqualTo("1");
				assertThat(((Number) JsonPath.read(event.data(), "$.seq")).longValue()).isEqualTo(1L);
				assertThat((String) JsonPath.read(event.data(), "$.type")).isEqualTo("SCHEDULE_UPDATED");
				assertThat((String) JsonPath.read(event.data(), "$.team.name")).isEqualTo("블랙홀");
				assertThat((String) JsonPath.read(event.data(), "$.actor.name")).isEqualTo("박준하");
				assertThat(((Number) JsonPath.read(event.data(), "$.target.id")).longValue()).isEqualTo(77L);
			}
		}

		@Test
		@DisplayName("서버 1의 API로 만든 알림이 서버 2에 연결한 사람에게 온다")
		void acrossServers() throws Exception {
			try (SseClient client = open(baseUrlOf(server2), memberA, "0")) {
				String body = """
						{"title":"화요일 연습","startAt":"2026-11-25T19:00:00+09:00","durationMinutes":120,
						 "votingEnabled":true,"voteDeadline":"2026-11-24T19:00:00+09:00"}
						""";
				HttpResponse<String> created = HttpClient.newHttpClient()
					.send(HttpRequest.newBuilder(URI.create(server1() + "/api/teams/" + teamId + "/schedules"))
						.header("Authorization", bearer(leader))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body))
						.build(), HttpResponse.BodyHandlers.ofString());
				assertThat(created.statusCode()).isEqualTo(201);

				Event event = client.next(WAIT);
				assertThat(event).isNotNull();
				assertThat(event.id()).isEqualTo("1");
				assertThat((String) JsonPath.read(event.data(), "$.type")).isEqualTo("SCHEDULE_CREATED");
				assertThat((String) JsonPath.read(event.data(), "$.target.name")).isEqualTo("화요일 연습");
			}
		}

		@Test
		@DisplayName("Last-Event-ID로 다시 연결하면 그 순번 뒤의 알림만 순번 순서대로 오고, 받은 알림은 다시 오지 않는다")
		void resumesAfterLastEventId() throws Exception {
			notifyA(3);

			try (SseClient client = open(server1(), memberA, "1")) {
				assertThat(receiveIds(client, 2)).containsExactly("2", "3");
				assertThat(client.next(Duration.ofMillis(500))).isNull();
			}
		}

		@Test
		@DisplayName("끊겨 있는 사이에 생긴 알림이 다시 연결하면 모두 온다")
		void catchesUpAfterDisconnect() throws Exception {
			try (SseClient client = open(server1(), memberA, "0")) {
				notifyA(1);
				assertThat(receiveIds(client, 1)).containsExactly("1");
			}

			notifyA(2);

			try (SseClient client = open(baseUrlOf(server2), memberA, "1")) {
				assertThat(receiveIds(client, 2)).containsExactly("2", "3");
			}
		}

		@Test
		@DisplayName("같은 사람이 Last-Event-ID가 다른 연결 두 개를 열면 각 연결이 자기 순번 뒤의 알림을 받는다")
		void twoConnectionsOfOnePerson() throws Exception {
			notifyA(2);

			try (SseClient behind = open(server1(), memberA, "0"); SseClient upToDate = open(server1(), memberA, "2")) {
				notifyA(1);

				assertThat(receiveIds(behind, 3)).containsExactly("1", "2", "3");
				assertThat(receiveIds(upToDate, 1)).containsExactly("3");
			}
		}

		@Test
		@DisplayName("다른 사람의 알림은 오지 않는다")
		void onlyMine() throws Exception {
			try (SseClient client = open(server1(), memberB, "0")) {
				notifyA(1);

				assertThat(client.next(Duration.ofMillis(800))).isNull();
			}
		}

		@Test
		@DisplayName("Last-Event-ID 없이 연결하면 연결한 뒤에 생긴 알림만 온다")
		void withoutLastEventId() throws Exception {
			notifyA(2);

			try (SseClient client = open(server1(), memberA, null)) {
				notifyA(1);

				assertThat(receiveIds(client, 1)).containsExactly("3");
				assertThat(client.next(Duration.ofMillis(500))).isNull();
			}
		}

		@Test
		@DisplayName("매긴 순번보다 큰 Last-Event-ID로 연결해도 다음 알림부터 정상으로 온다")
		void lastEventIdAboveLastSeq() throws Exception {
			notifyA(1);

			try (SseClient client = open(server1(), memberA, "99")) {
				notifyA(1);

				assertThat(receiveIds(client, 1)).containsExactly("2");
			}
		}

	}

	@Nested
	@DisplayName("따라잡기 상한")
	class CatchUpLimit {

		@Test
		@DisplayName("상한(3)만큼 밀렸으면 알림이 모두 온다")
		void atLimit() throws Exception {
			notifyA(3);

			try (SseClient client = open(server1(), memberA, "0")) {
				assertThat(receiveIds(client, 3)).containsExactly("1", "2", "3");
			}
		}

		@Test
		@DisplayName("상한을 넘게 밀렸으면 알림 없이 reset이 오고 연결이 닫힌다. 같은 계정의 밀리지 않은 연결은 알림을 그대로 받는다")
		void overLimit() throws Exception {
			notifyA(4);

			try (SseClient behind = open(server1(), memberA, "0"); SseClient upToDate = open(server1(), memberA, "4")) {
				Event event = behind.next(WAIT);
				assertThat(event).isNotNull();
				assertThat(event.event()).isEqualTo("reset");
				assertThat(behind.awaitClosed(WAIT)).as("reset 뒤에 닫힌다").isTrue();

				notifyA(1);
				assertThat(receiveIds(upToDate, 1)).containsExactly("5");
			}
		}

	}

	@Nested
	@DisplayName("연결 관리")
	class Connection {

		@Test
		@DisplayName("접근 토큰의 만료 시각이 되면 서버가 연결을 닫는다")
		void closesAtTokenExpiry() throws Exception {
			String token = bearer(memberA);
			clock.set(clock.instant().plus(AccessTokenIssuer.TTL).minusSeconds(2));

			try (SseClient client = SseClient.connect(server1(), token, "0")) {
				assertThat(client.status()).isEqualTo(200);
				assertThat(client.awaitClosed(Duration.ofSeconds(5))).as("만료 시각에 닫힌다").isTrue();
			}
		}

		@Test
		@DisplayName("보낸 것이 없으면 빈 신호(:)가 온다")
		void heartbeat() throws Exception {
			try (SseClient client = open(server1(), memberA, "0")) {
				Event event = client.nextIncludingComments(Duration.ofSeconds(3));

				assertThat(event).isNotNull();
				assertThat(event.comment()).isTrue();
			}
		}

		@Test
		@DisplayName("서버를 끄면(애플리케이션 컨텍스트를 닫음) 연결이 닫힌다")
		void closesOnShutdown() throws Exception {
			ConfigurableApplicationContext server = startServer();
			try (SseClient client = open(baseUrlOf(server), memberA, "0")) {
				server.close();

				assertThat(client.awaitClosed(Duration.ofSeconds(10))).as("서버가 꺼지며 닫는다").isTrue();
			}
			finally {
				server.close();
			}
		}

		@Test
		@DisplayName("연결이 없으면 감지기가 쿼리를 보내지 않는다")
		void noQueryWithoutConnections() throws Exception {
			long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (registry.connectionCount() > 0 && System.nanoTime() < until) {
				Thread.sleep(50);
			}
			assertThat(registry.connectionCount()).as("앞 테스트의 연결이 모두 빠졌다").isZero();
			clearInvocations(inboxRepository);

			Thread.sleep(500);

			verify(inboxRepository, never()).findLastSeqs(anyCollection());
		}

	}

	@Nested
	@DisplayName("오류")
	class Errors {

		@Test
		@DisplayName("로그인하지 않으면 401이다")
		void unauthorized() throws Exception {
			try (SseClient client = SseClient.connect(server1(), null, null)) {
				assertThat(client.status()).isEqualTo(401);
			}
		}

		@Test
		@DisplayName("숫자가 아닌 Last-Event-ID는 400 INVALID_REQUEST, 음수는 400 INVALID_NOTIFICATION_SEQ다")
		void invalidLastEventId() throws Exception {
			try (SseClient client = SseClient.connect(server1(), bearer(memberA), "abc")) {
				assertThat(client.status()).isEqualTo(400);
				assertThat((String) JsonPath.read(client.body(), "$.code")).isEqualTo("INVALID_REQUEST");
			}
			try (SseClient client = SseClient.connect(server1(), bearer(memberA), "-1")) {
				assertThat(client.status()).isEqualTo(400);
				assertThat((String) JsonPath.read(client.body(), "$.code")).isEqualTo("INVALID_NOTIFICATION_SEQ");
			}
		}

	}

}
