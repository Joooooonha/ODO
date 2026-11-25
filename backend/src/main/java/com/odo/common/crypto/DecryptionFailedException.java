package com.odo.common.crypto;

/**
 * 저장된 암호문을 풀지 못했다. 키가 틀렸거나, 모르는 버전이거나, 암호문이 바뀐 경우다.
 * 응답은 500이다. 메시지에는 저장된 값을 넣지 않는다.
 */
public class DecryptionFailedException extends RuntimeException {

	public DecryptionFailedException(String message) {
		super(message);
	}

	public DecryptionFailedException(String message, Throwable cause) {
		super(message, cause);
	}

}
