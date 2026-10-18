package SeSAC.Dance_Assessment.Config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * 운영 프로필에서 필수 비밀값이 모두 설정됐는지 <b>기동 초기에</b> 확인하고, 하나라도 빠지면 기동을 멈춘다.
 * 기동 확인이 실패하면 배포가 직전 jar로 자동 롤백된다.
 *
 * <p><b>왜 따로 확인하나.</b> 운영 설정은 {@code ${JWT_SECRET}}처럼 기본값 없는 자리 표시자인데, 값을 읽는
 * 방식에 따라 빠졌을 때의 동작이 다르다.
 * <ul>
 *   <li>{@code @Value}로 읽는 값(jwt.secret 등)은 해석하지 못하면 즉시 실패한다</li>
 *   <li>{@code @ConfigurationProperties}로 묶이는 값(소셜 로그인 키, Redis)은 실패하지 않고
 *       {@code "${KAKAO_CLIENT_ID}"}가 <b>글자 그대로</b> 남는다(spring-boot
 *       {@code PropertySourcesPlaceholdersResolver} 확인) — 그대로 뜨면 로그인이 조용히 깨진다</li>
 * </ul>
 * 그래서 목록을 한곳에 두고 {@link Environment#getRequiredProperty}로 엄격하게 읽는다(이 경로는 안쪽
 * 자리 표시자를 해석하지 못하면 "Could not resolve placeholder"로 실패한다).
 */
@Configuration
@Profile("prod")
public class ProdSecretsCheck {

    static final List<String> REQUIRED = List.of(
            "jwt.secret",
            "spring.security.oauth2.client.registration.kakao.client-id",
            "spring.security.oauth2.client.registration.kakao.client-secret",
            "spring.security.oauth2.client.registration.naver.client-id",
            "spring.security.oauth2.client.registration.naver.client-secret",
            "spring.data.redis.host",
            "spring.data.redis.port",
            "spring.data.redis.password");

    public ProdSecretsCheck(Environment environment) {
        for (String key : REQUIRED) {
            String value;
            try {
                value = environment.getRequiredProperty(key);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IllegalStateException("운영 필수 설정이 비어 있습니다: " + key + " — " + e.getMessage()
                        + " (/etc/dance-api.env 확인)", e);
            }
            if (value.isBlank()) {
                throw new IllegalStateException("운영 필수 설정이 비어 있습니다: " + key + " (/etc/dance-api.env 확인)");
            }
        }
    }
}
