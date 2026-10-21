package SeSAC.Dance_Assessment.Config;

import SeSAC.Dance_Assessment.Security.CookieOAuth2AuthorizationRequestRepository;
import SeSAC.Dance_Assessment.Security.CustomOAuth2UserService;
import SeSAC.Dance_Assessment.Security.JwtAuthenticationFilter;
import SeSAC.Dance_Assessment.Security.OAuth2SuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 인증·인가 설정.
 *
 * <p><b>여기가 생기기 전까지 이 서비스에는 사실상 인증이 없었다.</b>
 * 클라이언트가 보낸 {@code X-User-Id} 헤더를 그대로 신뢰했기 때문에,
 * 헤더 숫자만 바꾸면 남의 연습 기록과 영상을 볼 수 있었다.
 * 영상 조회 권한 검사도 소유권 검사도 전부 그 위에 얹혀 있어 함께 무력했다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomOAuth2UserService oAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final CorsConfigurationSource corsConfigurationSource;
    private final CookieOAuth2AuthorizationRequestRepository authorizationRequests;
    private final Environment environment;

    /**
     * 측정 환경(loadtest 프로필)에서만 로그인 없이 여는 actuator 경로. 그 프로필이 아니면 목록에 넣지 않는다 — 운영(prod)은
     * actuator를 하나도 노출하지 않고(exclude=*), 노출하지 않은 경로는 로그인 검사에 걸려 401이다.
     * 관리 포트(9091)에도 이 보안 설정이 그대로 걸린다(LoadtestProfileTest)
     */
    static final String[] LOADTEST_ACTUATOR = {
            "/actuator/prometheus",          // 수집기(Prometheus)
            "/actuator/health",
            "/actuator/loadtest",            // 측정 전용 장애 주입(LoadtestFaultEndpoint, 10/20 작업 5-3·5-4)
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // 세션을 쓰지 않고 JWT로만 인증하므로 CSRF 토큰 방식이 맞지 않는다.
                // 쿠키는 SameSite=Lax라 교차 사이트 POST에는 실리지 않는다.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 인증 실패 시 로그인 페이지로 리다이렉트하지 않고 401을 준다.
                // API 서버라 HTML 리다이렉트를 받으면 프론트가 처리하기 곤란하다.
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                .authorizeHttpRequests(auth -> {
                    auth
                        .requestMatchers(
                                "/api/auth/**",     // 로그인·회원가입 (컨트롤러는 /api 접두사가 붙는다)
                                "/oauth2/**",       // 소셜 로그인 시작
                                "/login/**",        // 소셜 로그인 콜백
                                // 개발용 로컬 저장소. 로그인 쿠키가 아니라 URL의
                                // HMAC 서명으로 통제한다 — presigned URL의 동작
                                // 방식이 그렇고, 분석 서버는 세션이 없다.
                                "/api/local-storage",
                                // VAPID 공개키. 이름 그대로 공개해도 되는 값이고,
                                // 알림 구독 UI를 그릴지 판단하려면 로그인 전에도 필요하다.
                                "/api/push/public-key",
                                "/error"
                        ).permitAll();
                    if (environment.acceptsProfiles(Profiles.of("loadtest"))) {
                        auth.requestMatchers(LOADTEST_ACTUATOR).permitAll();
                    }
                    auth
                        // 영상 재생은 <video src>로 불러서 쿠키가 실리지만,
                        // 권한 검사는 VideoService.playVideo가 직접 한다.
                        .anyRequest().authenticated();
                })

                .oauth2Login(oauth -> oauth
                        // 인가 요청을 세션 대신 서명한 쿠키에(앱 서버 두 대 — 시작과 콜백이 다른 서버로 가도 된다)
                        .authorizationEndpoint(a -> a.authorizationRequestRepository(authorizationRequests))
                        .userInfoEndpoint(u -> u.userService(oAuth2UserService))
                        .successHandler(oAuth2SuccessHandler))

                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
