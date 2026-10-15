package SeSAC.Dance_Assessment.Common;

import lombok.Getter;

/**
 * {@link ErrorCode}의 상태 코드와 {@code code}로 응답되는 예외.
 * 처리는 {@code GlobalExceptionHandler}가 한다.
 */
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
