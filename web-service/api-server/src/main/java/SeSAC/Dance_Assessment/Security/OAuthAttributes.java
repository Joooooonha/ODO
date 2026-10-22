package SeSAC.Dance_Assessment.Security;

import SeSAC.Dance_Assessment.Domain.AuthProvider;

import java.util.Map;

/**
 * 공급자마다 다른 응답 모양을 하나로 맞춘다.
 *
 * <p>카카오와 네이버는 같은 정보를 전혀 다른 구조로 준다:
 *
 * <pre>
 * 카카오: { "id": 12345, "properties": { "nickname": "홍길동" } }
 * 네이버: { "response": { "id": "abc", "nickname": "홍길동" } }
 * </pre>
 *
 * <p><b>이메일은 받지 않는다.</b> 카카오는 이메일 제공에 비즈앱 전환이나 검수가
 * 필요할 수 있고 사용자가 동의를 거부할 수도 있는데, 고유 id는 항상 오고 바뀌지
 * 않는다. 그래서 id를 계정 식별자로 쓴다.
 *
 * @param provider   공급자
 * @param providerId 공급자가 발급한 고유 id
 * @param nickname   표시용 이름. 못 받으면 대체값이 들어간다
 */
public record OAuthAttributes(AuthProvider provider, String providerId, String nickname) {

    public static OAuthAttributes of(String registrationId, Map<String, Object> attributes) {
        AuthProvider provider = AuthProvider.fromRegistrationId(registrationId);
        return switch (provider) {
            case KAKAO -> ofKakao(attributes);
            case NAVER -> ofNaver(attributes);
            default -> throw new IllegalArgumentException("지원하지 않는 공급자: " + registrationId);
        };
    }

    @SuppressWarnings("unchecked")
    private static OAuthAttributes ofKakao(Map<String, Object> attributes) {
        String id = String.valueOf(attributes.get("id"));
        String nickname = null;

        Object properties = attributes.get("properties");
        if (properties instanceof Map<?, ?> map) {
            nickname = (String) ((Map<String, Object>) map).get("nickname");
        }
        // properties가 비면 kakao_account.profile 쪽에 있을 수 있다
        if (nickname == null && attributes.get("kakao_account") instanceof Map<?, ?> account) {
            Object profile = ((Map<String, Object>) account).get("profile");
            if (profile instanceof Map<?, ?> p) {
                nickname = (String) ((Map<String, Object>) p).get("nickname");
            }
        }
        return new OAuthAttributes(AuthProvider.KAKAO, id, fallback(nickname, "카카오 사용자"));
    }

    @SuppressWarnings("unchecked")
    private static OAuthAttributes ofNaver(Map<String, Object> attributes) {
        // 네이버는 실제 값이 한 겹 안쪽 "response"에 들어 있다
        Object response = attributes.get("response");
        if (!(response instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("네이버 응답 형식이 예상과 다릅니다: " + attributes.keySet());
        }
        Map<String, Object> r = (Map<String, Object>) map;
        return new OAuthAttributes(
                AuthProvider.NAVER,
                String.valueOf(r.get("id")),
                fallback((String) r.get("nickname"), "네이버 사용자"));
    }

    private static String fallback(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
