package com.odo.common.time;

import java.io.IOException;
import java.time.Duration;

/**
 * 기준 시간 서버와 이 서버 시계의 차이를 잰다. 테스트는 가짜 구현을 쓴다.
 */
public interface SntpTimeSource {

	/**
	 * 이 서버의 시계가 기준보다 얼마나 늦은지. 양수면 이 서버가 뒤처졌고, 음수면 앞서 있다.
	 * 잰 값의 오차는 왕복 시간의 절반 정도다. 응답이 없거나 읽을 수 없으면 IOException.
	 */
	Duration measureOffset(String host) throws IOException;

}
