package SeSAC.Dance_Assessment.Security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * 소셜 로그인 인가 요청(state·redirect_uri·PKCE 등)을 <b>서버 세션이 아니라 서명한 쿠키</b>에 둔다(10/21 분산 구성).
 *
 * <p>Spring 기본값({@code HttpSessionOAuth2AuthorizationRequestRepository})은 서버 메모리의 세션에 넣는다. 앱 서버가
 * 두 대가 되면 로그인 시작({@code /oauth2/authorization/kakao})과 콜백({@code /login/oauth2/code/kakao})이 다른
 * 서버로 갈 수 있고, 그러면 콜백 서버에 인가 요청이 없어 로그인이 실패한다. 쿠키에 두면 어느 서버든 읽는다.
 *
 * <ul>
 *   <li><b>서명</b>: 값은 {@code base64url(직렬화) . base64url(HMAC-SHA256)}. 키는 JWT 비밀값에서 용도를 붙여
 *       만든다(두 서버가 같은 값). 서명을 먼저 확인하고, 맞을 때만 역직렬화한다 — 조작한 쿠키로 임의 객체를
 *       역직렬화하게 만들 수 없다</li>
 *   <li>HttpOnly, Secure(운영), SameSite=Lax(공급자에서 돌아오는 GET 리다이렉트에 실린다), 수명 3분, 경로 /</li>
 *   <li>콜백에서 꺼내면 바로 지운다(한 번만 쓴다)</li>
 * </ul>
 */
@Slf4j
@Component
public class CookieOAuth2AuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String NAME = "oauth2_auth_request";
    private static final int MAX_AGE_SECONDS = 180;

    private final boolean secure;
    private final byte[] key;

    public CookieOAuth2AuthorizationRequestRepository(@Value("${app.cookie.secure:true}") boolean secure,
                                                      @Value("${jwt.secret}") String jwtSecret) {
        this.secure = secure;
        this.key = derive(jwtSecret);
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(c -> NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .map(this::decode)
                .filter(r -> r != null)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
                                         HttpServletResponse response) {
        if (authorizationRequest == null) {
            write(response, "", 0);
            return;
        }
        write(response, encode(authorizationRequest), MAX_AGE_SECONDS);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest loaded = loadAuthorizationRequest(request);
        write(response, "", 0);
        return loaded;
    }

    String encode(OAuth2AuthorizationRequest authorizationRequest) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(authorizationRequest);
        } catch (IOException e) {
            throw new IllegalStateException("인가 요청을 쿠키로 바꾸지 못했습니다", e);
        }
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        byte[] payload = bytes.toByteArray();
        return b64.encodeToString(payload) + "." + b64.encodeToString(sign(payload));
    }

    OAuth2AuthorizationRequest decode(String value) {
        int dot = value == null ? -1 : value.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            Base64.Decoder b64 = Base64.getUrlDecoder();
            byte[] payload = b64.decode(value.substring(0, dot));
            byte[] mac = b64.decode(value.substring(dot + 1));
            if (!MessageDigest.isEqual(sign(payload), mac)) {
                log.warn("[소셜 로그인] 인가 요청 쿠키 서명 불일치 — 버림");
                return null;
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(payload))) {
                return in.readObject() instanceof OAuth2AuthorizationRequest r ? r : null;
            }
        } catch (IllegalArgumentException | IOException | ClassNotFoundException e) {
            log.warn("[소셜 로그인] 인가 요청 쿠키를 읽지 못함 — 버림 원인={}", e.toString());
            return null;
        }
    }

    private void write(HttpServletResponse response, String value, int maxAge) {
        Cookie cookie = new Cookie(NAME, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JWT 서명 키를 그대로 쓰지 않고 용도를 붙여 따로 만든다 */
    private static byte[] derive(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal("oauth2-authorization-request-cookie".getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
