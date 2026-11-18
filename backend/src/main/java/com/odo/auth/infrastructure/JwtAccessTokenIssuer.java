package com.odo.auth.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.odo.auth.application.AccessTokenIssuer;
import com.odo.common.config.OdoProperties;
import org.springframework.stereotype.Component;

/**
 * AccessTokenIssuer의 JWT(HS256) 구현. Nimbus로 서명한다.
 * 검증은 SecurityConfig의 JwtDecoder가 한다.
 */
@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

	private final MACSigner signer;

	private final Clock clock;

	JwtAccessTokenIssuer(OdoProperties properties, Clock clock) {
		try {
			this.signer = new MACSigner(properties.auth().jwtSecretBytes());
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("JWT 비밀키가 HS256에 맞지 않는다", ex);
		}
		this.clock = clock;
	}

	@Override
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
