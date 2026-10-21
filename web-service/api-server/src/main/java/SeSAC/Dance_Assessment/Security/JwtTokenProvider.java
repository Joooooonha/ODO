package SeSAC.Dance_Assessment.Security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 발급·검증.
 *
 * <p>토큰에는 <b>사용자 id만</b> 담는다. 닉네임이나 팀 같은 값은 바뀔 수 있는데
 * 토큰은 만료 전까지 갱신되지 않으므로, 담아두면 낡은 값이 권한 판단에 쓰인다.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /**
     * 유효 기간. 짧게 두고 재로그인시키는 것이 원칙이지만, 리프레시 토큰이 아직
     * 없으므로 사용자가 자주 튕기지 않을 정도로 잡았다. 리프레시를 도입하면
     * 이 값을 크게 줄인다.
     */
    private static final Duration VALIDITY = Duration.ofDays(7);

    private final SecretKey key;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256은 최소 256비트(32바이트) 키를 요구한다. 짧은 값이 설정되면
        // 여기서 즉시 실패하는 편이 낫다 — 조용히 약한 키로 돌아가면 안 된다.
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret이 너무 짧습니다. 32바이트(영문 32자) 이상이어야 합니다. 현재: "
                            + bytes.length + "바이트");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String createToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + VALIDITY.toMillis()))
                .signWith(key)
                .compact();
    }

    /**
     * @return 토큰이 유효하면 사용자 id, 아니면 {@code null}.
     *         만료·위조 모두 "인증 안 됨"으로 같게 취급하므로 예외를 던지지 않는다.
     */
    public Long parseUserId(String token) {
        return parse(token).map(TokenClaims::userId).orElse(null);
    }

    /**
     * 사용자 id와 만료 시각. 웹소켓은 핸드셰이크 때 한 번만 인증하므로, 연결 중에 토큰이 만료되면
     * 서버가 주기적으로 끊는다 — 그때 쓸 만료 시각이 필요하다.
     *
     * @return 유효하면 값, 만료·위조면 empty
     */
    public Optional<TokenClaims> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new TokenClaims(
                    Long.valueOf(claims.getSubject()), claims.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 검증 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public record TokenClaims(Long userId, Instant expiresAt) {
    }

    public Duration getValidity() {
        return VALIDITY;
    }
}
