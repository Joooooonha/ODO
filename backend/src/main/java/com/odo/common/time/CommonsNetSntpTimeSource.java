package com.odo.common.time;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.springframework.stereotype.Component;

/**
 * Apache Commons Net의 NTPUDPClient로 SNTP 요청을 한 번 보내 시계 차이를 잰다(UDP 123).
 * 시계 차이와 왕복 시간 계산은 라이브러리에 맡긴다. 라이브러리가 ms 단위로 계산하므로 잰 값도 ms 단위다.
 */
@Component
public class CommonsNetSntpTimeSource implements SntpTimeSource {

	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	@Override
	public Duration measureOffset(String host) throws IOException {
		NTPUDPClient client = new NTPUDPClient();
		client.setDefaultTimeout(TIMEOUT);
		try {
			client.open();
			TimeInfo info = client.getTime(InetAddress.getByName(host));
			info.computeDetails();
			Long offsetMillis = info.getOffset();
			if (offsetMillis == null) {
				throw new IOException("시계 차이를 계산할 수 없는 응답이다: " + info.getComments());
			}
			return Duration.ofMillis(offsetMillis);
		}
		finally {
			client.close();
		}
	}

}
