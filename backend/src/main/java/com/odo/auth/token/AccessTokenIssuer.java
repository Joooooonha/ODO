package com.odo.auth.token;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.odo.common.OdoProperties;
import org.springframework.stereotype.Component;

/**
 * 접근 토큰(JWT, HS256, 30분) 발급. 본문에는 sub(계정 ID), iat, exp만 넣는다.
 * 팀 역할은 요청마다 DB에서 확인하므로 넣지 않는다. 검증은 SecurityConfig의 JwtDecoder가 한다.
 */
@Component
public class AccessTokenIssuer {

	public static final Duration TTL = Duration.ofMinutes(30);

	private final MACSigner signer;

	private final Clock clock;

	public AccessTokenIssuer(OdoProperties properties, Clock clock) {
		try {
			this.signer = new MACSigner(properties.auth().jwtSecretBytes());
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("JWT 비밀키가 HS256에 맞지 않는다", ex);
		}
		this.clock = clock;
	}

	public String issue(long accountId) {
		Instant now = clock.instant();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(String.valueOf(accountId))
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plus(TTL)))
			.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		try {
			jwt.sign(signer);
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("접근 토큰 서명 실패", ex);
		}
		return jwt.serialize();
	}

}
