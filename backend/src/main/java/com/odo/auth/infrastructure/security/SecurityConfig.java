package com.odo.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;

import com.odo.auth.infrastructure.security.CookieOAuth2AuthorizationRequestRepository;
import com.odo.auth.presentation.LoginFailureHandler;
import com.odo.auth.presentation.LoginSuccessHandler;
import com.odo.auth.presentation.RefreshTokenCookies;
import com.odo.common.config.OdoProperties;
import com.odo.common.crypto.SignedCookieCodec;
import com.odo.common.error.ErrorResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 인증 구성. 12_Account_spec.md의 "설계".
 * <ul>
 * <li>서버 세션을 쓰지 않는다. 카카오 로그인의 state는 서명된 쿠키에 둔다.</li>
 * <li>접근 토큰은 Authorization 헤더의 JWT. 서명과 만료만 확인하고 DB를 조회하지 않는다.</li>
 * <li>갱신과 로그아웃(/api/auth/**)은 갱신 토큰 쿠키로만 동작한다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	private static final ErrorResponse UNAUTHENTICATED = new ErrorResponse("UNAUTHENTICATED",
			"접근 토큰이 없거나 유효하지 않다");

	/**
	 * CSRF 방어를 끈 이유: 접근 토큰은 쿠키가 아니라 헤더로 오므로 다른 사이트가 붙일 수 없다.
	 * 쿠키로 동작하는 갱신과 로그아웃은 갱신 토큰 쿠키의 SameSite=Strict가 다른 사이트의 요청을 막는다.
	 */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			CookieOAuth2AuthorizationRequestRepository authorizationRequestRepository,
			LoginSuccessHandler loginSuccessHandler, LoginFailureHandler loginFailureHandler, JwtDecoder jwtDecoder)
			throws Exception {
		AuthenticationEntryPoint unauthenticated = (request, response, exception) -> {
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write(UNAUTHENTICATED.toJson());
		};

		http.csrf(csrf -> csrf.disable())
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(cache -> cache.requestCache(new NullRequestCache()))
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.logout(logout -> logout.disable())
			.authorizeHttpRequests(auth -> auth.requestMatchers(RefreshTokenCookies.PATH + "/**")
				.permitAll()
				.requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**")
				.permitAll()
				.anyRequest()
				.authenticated())
			.oauth2Login(login -> login
				.authorizationEndpoint(endpoint -> endpoint.authorizationRequestRepository(authorizationRequestRepository))
				// 카카오 토큰은 로그인 뒤 쓰지 않으므로 서버 메모리에 남기지 않는다
				.authorizedClientRepository(new NoOpOAuth2AuthorizedClientRepository())
				.successHandler(loginSuccessHandler)
				.failureHandler(loginFailureHandler))
			.oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(jwtDecoder))
				.bearerTokenResolver(bearerTokenResolver())
				.authenticationEntryPoint(unauthenticated))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthenticated));
		return http.build();
	}

	@Bean
	CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository(OdoProperties properties,
			Clock clock) {
		return new CookieOAuth2AuthorizationRequestRepository(
				new SignedCookieCodec(properties.auth().oauthCookieSecretBytes(), clock));
	}

	/** HS256 서명과 만료를 확인한다. 만료 판단에 허용 오차를 두지 않는다(30분이 지나면 바로 거부). */
	@Bean
	JwtDecoder jwtDecoder(OdoProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder
			.withSecretKey(new SecretKeySpec(properties.auth().jwtSecretBytes(), "HmacSHA256"))
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
		timestamps.setClock(clock);
		JwtClaimValidator<Instant> expRequired = new JwtClaimValidator<>(JwtClaimNames.EXP, Objects::nonNull);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, expRequired));
		return decoder;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(OdoProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(List.of(properties.frontendBaseUrl()));
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		// Last-Event-ID: 알림 실시간 연결이 마지막으로 받은 순번을 보낸다. 허락하지 않으면 브라우저가 사전 요청에서 연결을 막는다
		// (11_Notification_spec.md의 "실시간 연결").
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Last-Event-ID"));
		// 갱신과 로그아웃 요청에 갱신 토큰 쿠키를 받기 위해 필요하다
		cors.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

	/**
	 * 갱신과 로그아웃 요청에는 Authorization 헤더가 붙어 와도 읽지 않는다.
	 * 프론트가 만료된 접근 토큰을 모든 요청에 붙여도 갱신이 401로 막히지 않게 한다.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
		String authPathPrefix = RefreshTokenCookies.PATH + "/";
		return request -> request.getRequestURI().startsWith(authPathPrefix) ? null : delegate.resolve(request);
	}

}
