import { createHmac } from 'node:crypto';

/**
 * 측정 환경 전용: 측정 환경의 개발용 비밀키로 로그인 쿠키(JWT)를 직접 만든다 — 로그인 흐름을 수천 번 돌지 않게.
 * 서버(JwtTokenProvider)는 sub = 사용자 id, 만료 시각만 본다. 키는 UTF-8 바이트 그대로(32바이트 이상)라
 * HS256으로 서명하면 받아들인다. **운영 비밀키는 절대 쓰지 않는다.**
 */
const b64url = (buf) => Buffer.from(buf).toString('base64url');

export function signToken(secret, userId, ttlSeconds = 24 * 3600) {
  if (Buffer.byteLength(secret, 'utf8') < 32) throw new Error('JWT 비밀키는 32바이트 이상');
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({ sub: String(userId), iat: now, exp: now + ttlSeconds }));
  const signature = createHmac('sha256', Buffer.from(secret, 'utf8')).update(`${header}.${payload}`).digest('base64url');
  return `${header}.${payload}.${signature}`;
}

export const COOKIE_NAME = 'access_token';
