package com.odo.common.storage;

/**
 * 저장소에 닿지 못했거나(연결 실패, 시간 초과, 5xx) 응답을 읽을 수 없다. 버그가 아니라 잠시 뒤 다시 보내면 되는 경우라
 * 503 STORAGE_UNAVAILABLE로 답한다.
 */
public class StorageUnavailableException extends RuntimeException {

	public StorageUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
