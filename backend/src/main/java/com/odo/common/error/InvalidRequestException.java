package com.odo.common.error;

/**
 * 요청 본문은 JSON으로 읽었지만 꼭 있어야 할 값이 빠졌다. 본문을 읽지 못한 것과 같은 INVALID_REQUEST(400)로 답한다.
 * 예: 공연 명단 항목의 accountId나 role이 없음.
 */
public class InvalidRequestException extends RuntimeException {

	public InvalidRequestException() {
		super("요청을 읽을 수 없다");
	}

}
