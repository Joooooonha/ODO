package SeSAC.Dance_Assessment;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 모든 {@code @SpringBootTest}가 쓰는 테스트 DB — 진짜 PostgreSQL 16.
 *
 * <p><b>왜 H2가 아닌가.</b> H2는 행 잠금·부분 인덱스·읽기 전용 트랜잭션 동작이
 * PostgreSQL과 달라서, 통과한 테스트가 운영에서 깨지는 일이 있었다(읽기 전용
 * 트랜잭션의 INSERT를 H2는 받아줬다 — {@code TransactionBoundaryTest} 참고).
 * 스키마도 Hibernate가 아니라 운영과 같은 Flyway 마이그레이션으로 만든다.
 *
 * <p><b>컨테이너는 JVM에 하나다.</b> 테스트 클래스마다 Spring 컨텍스트가 따로
 * 생길 수 있지만 전부 이 컨테이너를 공유한다(정적 필드, 처음 쓸 때 시작).
 * 그래서 데이터도 공유된다 — 테스트마다 사용자·팀을 새로 만들어 섞이지 않게 한다.
 * 컨테이너는 JVM이 끝나면 Testcontainers가 치운다.
 *
 * <p>등록은 {@code src/test/resources/META-INF/spring.factories}에서 한다. 테스트
 * 클래스마다 붙이지 않아도 되고, 새 테스트가 빠뜨릴 일도 없다.
 *
 * <p>Docker가 없으면 여기서 실패한다. 건너뛰지 않는다 — 건너뛰면 초록불로 보여서
 * 테스트가 아무것도 확인하지 않은 채 통과한 것처럼 된다.
 */
public class PostgresTestContainer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    // 운영과 같은 메이저 버전(운영 16.15)
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword()
        ).applyTo(context.getEnvironment());
    }
}
