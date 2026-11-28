package com.odo.notification.application.stream;

import java.io.IOException;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * 실시간 연결 하나와 그 연결에 마지막으로 보낸 순번. 11_Notification_spec.md의 "연결: SSE".
 * 연결을 여는 요청 스레드와 감지기 스레드가 함께 보낼 수 있어 보내기와 순번 바꾸기를 이 객체로 묶어 한 번에 하나씩 한다.
 */
public final class NotificationStreamConnection {

	private final long accountId;

	private final SseEmitter emitter;

	private long sentSeq;

	private long lastWriteNanos;

	private boolean closed;

	NotificationStreamConnection(long accountId, SseEmitter emitter, long sentSeq) {
		this.accountId = accountId;
		this.emitter = emitter;
		this.sentSeq = sentSeq;
		this.lastWriteNanos = System.nanoTime();
	}

	public long accountId() {
		return accountId;
	}

	public synchronized long sentSeq() {
		return sentSeq;
	}

	/** 보낸 순번을 올린다. 줄이지 않는다. */
	synchronized void advanceTo(long seq) {
		sentSeq = Math.max(sentSeq, seq);
	}

	/** 마지막으로 보낸 뒤 지난 시간(나노초). 빈 신호를 보낼지 정할 때 쓴다. */
	synchronized long idleNanos() {
		return System.nanoTime() - lastWriteNanos;
	}

	/** 보내고 성공했는지 돌려준다. 브라우저가 연결을 닫아 실패하면 닫힌 연결이 된다. */
	synchronized boolean send(SseEventBuilder event) {
		if (closed) {
			return false;
		}
		try {
			emitter.send(event);
			lastWriteNanos = System.nanoTime();
			return true;
		}
		catch (IOException | IllegalStateException ex) {
			// 끊긴 연결이다. IOException이면 컨테이너가 오류로 끝내므로 따로 complete하지 않는다.
			closed = true;
			return false;
		}
	}

	/** 서버가 연결을 닫는다(토큰 만료, 서버 종료, reset). */
	synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		emitter.complete();
	}

}
