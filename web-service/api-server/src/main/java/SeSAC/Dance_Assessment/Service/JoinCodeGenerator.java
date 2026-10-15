package SeSAC.Dance_Assessment.Service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 팀 참여 코드.
 *
 * <p>사람이 받아 적어 입력하는 값이라 짧게(8자리), 헷갈리는 문자(0 O 1 I L)를 빼고
 * 만든다. 31자 × 8자리 = 약 8.5×10¹¹가지라 추측해서 맞히기는 사실상 불가능하다.
 * 예측할 수 없어야 하므로 {@link java.util.Random}이 아니라 {@link SecureRandom}을 쓴다.
 *
 * <p>V2 마이그레이션이 기존 팀에 발급할 때도 같은 문자 집합을 쓴다.
 */
@Component
public class JoinCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 사용자가 입력한 코드를 저장 형식으로 맞춘다. 소문자·공백·하이픈을 허용한다.
     *
     * @return 형식이 맞지 않으면 null (호출자는 "없는 코드"로 응답한다)
     */
    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        String code = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (code.length() != LENGTH) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) {
                return null;
            }
        }
        return code;
    }
}
