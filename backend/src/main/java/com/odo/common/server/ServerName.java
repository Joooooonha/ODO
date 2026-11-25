package com.odo.common.server;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 이 서버의 호스트 이름. K8s는 컨테이너의 호스트 이름을 파드 이름으로 정한다.
 * 투표 요청에 도착 시각을 찍은 서버를 남길 때 쓴다(03_Schedule_spec.md의 "서버 시계").
 * 뜰 때 한 번 읽는다. 저장 칼럼에 맞춰 63자(K8s 파드 이름의 최대 길이)에서 자른다.
 */
@Component
public class ServerName {

	private static final Logger log = LoggerFactory.getLogger(ServerName.class);

	static final int MAX_LENGTH = 63;

	private static final String UNKNOWN = "unknown";

	private final String value;

	public ServerName() {
		this.value = truncate(resolve());
	}

	public String value() {
		return value;
	}

	private static String resolve() {
		try {
			String hostName = InetAddress.getLocalHost().getHostName();
			if (hostName != null && !hostName.isBlank()) {
				return hostName;
			}
		}
		catch (UnknownHostException ex) {
			log.warn("호스트 이름을 읽지 못했다. HOSTNAME 환경 변수를 쓴다", ex);
		}
		String fromEnv = System.getenv("HOSTNAME");
		return fromEnv == null || fromEnv.isBlank() ? UNKNOWN : fromEnv;
	}

	private static String truncate(String name) {
		return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH);
	}

}
