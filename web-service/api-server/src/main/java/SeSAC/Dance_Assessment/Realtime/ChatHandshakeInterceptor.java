package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Security.JwtCookie;
import SeSAC.Dance_Assessment.Security.JwtTokenProvider;
import SeSAC.Dance_Assessment.Security.JwtTokenProvider.TokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

/**
 * 웹소켓 핸드셰이크에서 신원을 확정하고 연결 상태({@link ChatConnection})를 만든다(5.1, E5).
 *
 * <p><b>인증 자체는 여기서 하지 않는다.</b> 핸드셰이크는 일반 HTTP GET이라 httpOnly 쿠키가 실리고,
 * 기존 Security 필터 체인({@code JwtAuthenticationFilter} → {@code anyRequest().authenticated()})이
 * 먼저 검사해 실패면 401로 연결이 열리지 않는다. Origin은 엔드포인트의 허용 목록과 Security CORS가
 * 함께 막는다. 이 인터셉터는 인증된 사용자와 <b>토큰 만료 시각</b>을 기록한다 — 연결 중에 토큰이
 * 만료되면 주기 작업이 끊는다({@link TokenExpiryChecker}).
 *
 * <p>Principal은 Spring Security의 {@code Authentication}(principal = 사용자 id {@code Long})이다.
 * 이후 모든 STOMP 메시지에 이 사용자가 따라간다. 이름({@code getName()})은 id 문자열이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtCookie jwtCookie;
    private final JwtTokenProvider tokenProvider;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Long userId = userIdOf(request.getPrincipal());
        Optional<TokenClaims> claims = request instanceof ServletServerHttpRequest servlet
                ? jwtCookie.read(servlet.getServletRequest()).flatMap(tokenProvider::parse)
                : Optional.empty();

        if (userId == null || claims.isEmpty() || !userId.equals(claims.get().userId())) {
            // Security 필터가 이미 막았어야 한다. 여기까지 왔다면 설정이 바뀐 것이다.
            log.warn("[웹소켓 핸드셰이크 거부] 인증 정보 불일치 principal={} token={}", userId, claims.map(TokenClaims::userId));
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ChatConnection.ATTRIBUTE, new ChatConnection(userId, claims.get().expiresAt()));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private static Long userIdOf(Principal principal) {
        return principal instanceof Authentication auth && auth.getPrincipal() instanceof Long id ? id : null;
    }
}
