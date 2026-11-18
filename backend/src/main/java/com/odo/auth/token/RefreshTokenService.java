package com.odo.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 갱신 토큰의 발급, rotation, 10초 유예, 도난 탐지, 로그아웃, 만료 행 정리.
 * <p>
 * 갱신 토큰은 32바이트 난수이고 DB에는 SHA-256 해시만 둔다. 로그인 한 번에서 이어지는 토큰은 같은 family_id를 가진다.
 * 사용 기록을 조건부 UPDATE로 하기 위해 JPA 대신 JdbcTemplate을 쓴다.
 */
@Service
public class RefreshTokenService {

	public static final Duration TTL = Duration.ofDays(30);

	/** 같은 토큰이 이 시간 안에 다시 오면 동시 요청으로 보고 도난으로 판단하지 않는다. */
	public static final Duration REUSE_GRACE = Duration.ofSeconds(10);

	private static final int TOKEN_BYTES = 32;

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	private final SecureRandom random = new SecureRandom();

	private final JdbcTemplate jdbc;

	private final AccessTokenIssuer accessTokenIssuer;

	private final Clock clock;

	public RefreshTokenService(JdbcTemplate jdbc, AccessTokenIssuer accessTokenIssuer, Clock clock) {
		this.jdbc = jdbc;
		this.accessTokenIssuer = accessTokenIssuer;
		this.clock = clock;
	}

	/** 새 로그인(새 family)의 갱신 토큰을 발급하고 원문을 돌려준다. */
	@Transactional
	public String issueForNewLogin(long accountId) {
		return insert(accountId, UUID.randomUUID(), clock.instant());
	}

	/**
	 * 갱신. 실패하면 RefreshFailedException.
	 * 유예 기간 안의 재사용이면 newRefreshToken이 null이다.
	 * <p>
	 * 도난을 탐지해 family를 지운 뒤 예외를 던지므로, 그 삭제가 되돌려지지 않게 noRollbackFor를 둔다.
	 */
	@Transactional(noRollbackFor = RefreshFailedException.class)
	public RefreshResult refresh(String rawRefreshToken) {
		if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
			throw new RefreshFailedException("갱신 토큰 없음");
		}
		Instant now = clock.instant();
		String hash = hash(rawRefreshToken);

		// 한 요청만 rotation하게 한다. 동시에 온 다른 요청은 행 잠금을 기다린 뒤 used_at이 채워진 것을 보고 0행이 된다
		int rotated = jdbc.update(
				"UPDATE refresh_token SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?",
				Timestamp.from(now), hash, Timestamp.from(now));

		TokenRow row = find(hash);
		if (rotated == 1) {
			String newToken = insert(row.accountId(), row.familyId(), now);
			return new RefreshResult(accessTokenIssuer.issue(row.accountId()), newToken);
		}
		if (row == null) {
			throw new RefreshFailedException("DB에 없는 갱신 토큰");
		}
		if (!row.expiresAt().isAfter(now)) {
			throw new RefreshFailedException("만료된 갱신 토큰");
		}
		if (!now.isAfter(row.usedAt().plus(REUSE_GRACE))) {
			return new RefreshResult(accessTokenIssuer.issue(row.accountId()), null);
		}
		int deleted = deleteFamily(row.familyId());
		log.warn("이미 쓴 갱신 토큰이 다시 쓰였다. 도난으로 보고 family를 무효로 한다. account={}, family={}, deleted={}",
				row.accountId(), row.familyId(), deleted);
		throw new RefreshFailedException("이미 쓴 갱신 토큰의 재사용");
	}

	/** 그 토큰이 속한 family를 모두 지운다. 없는 토큰이어도 예외 없이 끝난다. */
	@Transactional
	public void logout(String rawRefreshToken) {
		if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
			return;
		}
		TokenRow row = find(hash(rawRefreshToken));
		if (row != null) {
			deleteFamily(row.familyId());
		}
	}

	/** 만료된 행을 지우고 지운 수를 돌려준다. 여러 서버가 동시에 실행해도 결과가 같다. */
	@Transactional
	public int deleteExpired() {
		return jdbc.update("DELETE FROM refresh_token WHERE expires_at <= ?", Timestamp.from(clock.instant()));
	}

	private String insert(long accountId, UUID familyId, Instant now) {
		String token = newRawToken();
		jdbc.update(
				"INSERT INTO refresh_token (account_id, family_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
				accountId, familyId, hash(token), Timestamp.from(now.plus(TTL)), Timestamp.from(now));
		return token;
	}

	private TokenRow find(String hash) {
		List<TokenRow> rows = jdbc.query(
				"SELECT account_id, family_id, expires_at, used_at FROM refresh_token WHERE token_hash = ?",
				(rs, rowNum) -> {
					Timestamp usedAt = rs.getTimestamp("used_at");
					return new TokenRow(rs.getLong("account_id"), rs.getObject("family_id", UUID.class),
							rs.getTimestamp("expires_at").toInstant(), usedAt == null ? null : usedAt.toInstant());
				}, hash);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private int deleteFamily(UUID familyId) {
		return jdbc.update("DELETE FROM refresh_token WHERE family_id = ?", familyId);
	}

	private String newRawToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record TokenRow(long accountId, UUID familyId, Instant expiresAt, Instant usedAt) {
	}

	public record RefreshResult(String accessToken, String newRefreshToken) {
	}

	public static class RefreshFailedException extends RuntimeException {

		public RefreshFailedException(String reason) {
			super(reason);
		}

	}

}
