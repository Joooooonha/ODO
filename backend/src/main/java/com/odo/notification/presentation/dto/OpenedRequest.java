package com.odo.notification.presentation.dto;

import com.odo.common.error.InvalidRequestException;

/** 알림함을 열었다. seq는 화면에 보인 맨 위 알림의 순번(없으면 0)이다. 없으면 INVALID_REQUEST다. */
public record OpenedRequest(
		Long seq) {

	public long requireSeq() {
		if (seq == null) {
			throw new InvalidRequestException();
		}
		return seq;
	}

}
