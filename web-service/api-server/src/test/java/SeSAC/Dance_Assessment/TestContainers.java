package SeSAC.Dance_Assessment;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 모든 {@code @SpringBootTest}가 쓰는 진짜 PostgreSQL 16과 Redis.
 *
 * <p><b>왜 H2가 아닌가.</b> H2는 행 잠금·부분 인덱스·읽기 전용 트랜잭션 동작이
 * PostgreSQL과 달라서, 통과한 테스트가 운영에서 깨지는 일이 있었다(읽기 전용
 * 트랜잭션의 INSERT를 H2는 받아줬다 — {@code TransactionBoundaryTest} 참고).
 * 스키마도 Hibernate가 아니라 운영과 같은 Flyway 마이그레이션으로 만든다.
 *
 * <p><b>Redis는 모든 컨텍스트에 필요하다.</b> 앱이 기동할 때 Redis에 PING을 보내고 실패하면 기동을
 * 멈추기 때문이다(RedisConfig). 채팅과 무관한 테스트도 마찬가지다.
 *
 * <p><b>컨테이너는 JVM에 하나씩이다.</b> 테스트 클래스마다 Spring 컨텍스트가 따로
 * 생길 수 있지만 전부 이 컨테이너를 공유한다(정적 필드, 처음 쓸 때 시작).
 * 그래서 데이터도 공유된다 — 테스트마다 사용자·팀을 새로 만들어 섞이지 않게 한다.
 * 컨테이너는 JVM이 끝나면 Testcontainers가 치운다.
 *
 * <p>등록은 {@code src/test/resources/META-INF/spring.factories}에서 한다. 테스트
 * 클래스마다 붙이지 않아도 되고, 새 테스트가 빠뜨릴 일도 없다. 테스트가 직접 띄우는 두 번째 서버
 * ({@code SpringApplicationBuilder})에도 똑같이 적용된다.
 *
 * <p>Docker가 없으면 여기서 실패한다. 건너뛰지 않는다 — 건너뛰면 초록불로 보여서
 * 테스트가 아무것도 확인하지 않은 채 통과한 것처럼 된다.
 */
public class TestContainers implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** 이 속성이 false면 Redis 접속 정보를 넣지 않는다(Redis 없이 기동 실패를 확인하는 테스트용). */
    public static final String INJECT_REDIS = "test.containers.redis.inject";

    public static final String REDIS_PASSWORD = "test-redis";

    // 운영과 같은 메이저 버전(운영 16.15).
    // max_connections는 테스트에서만 늘린다. 장애 주입용 스파이 빈 때문에 Spring 컨텍스트가 여러 개
    // 생기고, 컨텍스트마다 커넥션 풀(기본 10개)을 따로 잡는다. 기본값 100이면 빠듯하다.
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withCommand("postgres", "-c", "max_connections=300");

    // 운영은 Ubuntu 24.04 apt의 redis-server(7.0 계열). Pub/Sub만 쓰고 영속화하지 않는다.
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.0-alpine")
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD, "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /** 같은 서버의 다른 데이터베이스 주소. 마이그레이션 테스트가 빈 DB를 따로 만들 때 쓴다. */
    public static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + database;
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    /** Redis 장애를 흉내 낼 때(일시 정지 등) 쓴다. */
    public static GenericContainer<?> redis() {
        return REDIS;
    }

    /**
     * Redis에서 본 채널의 구독 연결 수({@code PUBSUB NUMSUB}). 컨테이너 안의 redis-cli로 묻는다 —
     * Spring Data Redis의 임의 명령 실행은 배열 응답을 읽지 못한다.
     */
    public static long redisSubscribers(String channel) {
        try {
            var result = REDIS.execInContainer("redis-cli", "--no-auth-warning", "-a", REDIS_PASSWORD,
                    "PUBSUB", "NUMSUB", channel);
            String[] lines = result.getStdout().trim().split("\\R");
            return Long.parseLong(lines[lines.length - 1].trim());
        } catch (Exception e) {
            throw new IllegalStateException("PUBSUB NUMSUB 실패: " + channel, e);
        }
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues values = TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword());
        if (context.getEnvironment().getProperty(INJECT_REDIS, Boolean.class, true)) {
            values = values.and(
                    "spring.data.redis.host=" + REDIS.getHost(),
                    "spring.data.redis.port=" + REDIS.getFirstMappedPort(),
                    "spring.data.redis.password=" + REDIS_PASSWORD);
        }
        values.applyTo(context.getEnvironment());
    }
}
