package com.odo.auth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.odo.auth.domain.RefreshToken;
import com.odo.auth.domain.RefreshTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * RefreshTokenRepository의 JDBC 구현.
 * 사용 기록을 조건부 UPDATE로 하고 family 단위로 지우는 SQL을 그대로 보이게 하려고 JPA 대신 JdbcTemplate을 쓴다.
 */
@Repository
class JdbcRefreshTokenRepository implements RefreshTokenRepository {

	private final JdbcTemplate jdbc;

	JdbcRefreshTokenRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insert(long accountId, UUID familyId, String tokenHash, Instant expiresAt, Instant createdAt) {
		jdbc.update(
				"INSERT INTO refresh_token (account_id, family_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
				accountId, familyId, tokenHash, Timestamp.from(expiresAt), Timestamp.from(createdAt));
	}

	/**
	 * 동시에 온 다른 UPDATE는 행 잠금을 기다린 뒤 used_at이 채워진 것을 보고 0행이 된다.
	 */
	@Override
	public boolean markUsedIfUnused(String tokenHash, Instant now) {
		int updated = jdbc.update(
				"UPDATE refresh_token SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?",
				Timestamp.from(now), tokenHash, Timestamp.from(now));
		return updated == 1;
	}

	@Override
	public Optional<RefreshToken> findByHash(String tokenHash) {
		List<RefreshToken> rows = jdbc.query(
				"SELECT account_id, family_id, expires_at, used_at FROM refresh_token WHERE token_hash = ?",
				(rs, rowNum) -> {
					Timestamp usedAt = rs.getTimestamp("used_at");
					return new RefreshToken(rs.getLong("account_id"), rs.getObject("family_id", UUID.class),
							rs.getTimestamp("expires_at").toInstant(), usedAt == null ? null : usedAt.toInstant());
				}, tokenHash);
		return rows.stream().findFirst();
	}

	@Override
	public int deleteFamily(UUID familyId) {
		return jdbc.update("DELETE FROM refresh_token WHERE family_id = ?", familyId);
	}

	@Override
	public int deleteExpired(Instant now) {
		return jdbc.update("DELETE FROM refresh_token WHERE expires_at <= ?", Timestamp.from(now));
	}

}
