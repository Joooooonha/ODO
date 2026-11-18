package com.odo.common.error;

/**
 * 오류 응답 본문. 프론트는 code로 분기하고, message는 개발자용이다.
 */
public record ErrorResponse(String code, String message) {

	/** JSON 문자열. 필터처럼 Jackson을 거치지 않는 곳에서 쓴다. code와 message에는 따옴표가 없다. */
	public String toJson() {
		return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
	}

}
