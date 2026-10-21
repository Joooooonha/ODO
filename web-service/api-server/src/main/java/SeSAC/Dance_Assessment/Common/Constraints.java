package SeSAC.Dance_Assessment.Common;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;

/** DB 제약 위반을 "어떤 제약인가"로 가려낸다. */
public final class Constraints {

    private Constraints() {
    }

    /**
     * 이 예외가 이름이 {@code constraint}인 제약을 어긴 것인가.
     *
     * <p>동시 요청이 UNIQUE에 걸린 경우만 409 같은 정상 응답으로 바꾸고, 다른 제약 위반은
     * 원인이 다르므로 그대로 던지기 위해 쓴다. Hibernate가 제약 이름을 뽑아주면 그것을,
     * 아니면 메시지를 본다(대소문자는 무시한다).
     */
    public static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String text = t instanceof ConstraintViolationException cve && cve.getConstraintName() != null
                    ? cve.getConstraintName()
                    : t.getMessage();
            if (text != null && text.toLowerCase(Locale.ROOT).contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
