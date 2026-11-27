package com.odo.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 실제 포트로 뜬 서버의 SSE 스트림을 줄 단위로 읽는 테스트용 클라이언트. 11_Notification_spec.md의 "실시간 (통합)".
 * 받은 줄을 SSE 규칙대로 이벤트로 묶어 큐에 쌓는다. 빈 줄이 이벤트 하나의 끝이다.
 * 필드 이름 뒤의 콜론 다음 공백 하나는 SSE 규칙대로 뺀다(id:44와 id: 44는 같다).
 */
public final class SseClient implements AutoCloseable {

	/** 이벤트 하나. comment가 true면 콜론으로 시작하는 줄(빈 신호)만 있던 묶음이다. */
	public record Event(String id, String event, String data, String retry, boolean comment) {

	}

	private final HttpClient http;

	private final int status;

	private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();

	private final List<String> rawLines = new ArrayList<>();

	private final CountDownLatch closed = new CountDownLatch(1);

	private final Stream<String> lines;

	private SseClient(HttpClient http, HttpResponse<Stream<String>> response) {
		this.http = http;
		this.status = response.statusCode();
		this.lines = response.body();
		Thread reader = new Thread(this::read, "sse-client");
		reader.setDaemon(true);
		reader.start();
	}

	/** bearer가 null이면 Authorization을, lastEventId가 null이면 Last-Event-ID를 붙이지 않는다. 응답 헤더가 오면 돌아온다. */
	public static SseClient connect(String baseUrl, String bearer, String lastEventId) throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/notifications/stream"))
			.header("Accept", "text/event-stream")
			.GET();
		if (bearer != null) {
			request.header("Authorization", bearer);
		}
		if (lastEventId != null) {
			request.header("Last-Event-ID", lastEventId);
		}
		HttpResponse<Stream<String>> response = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofLines())
			.get(10, TimeUnit.SECONDS);
		return new SseClient(http, response);
	}

	public int status() {
		return status;
	}

	/** 연결이 닫힐 때까지 기다린 뒤 받은 줄 전체. 오류 응답의 본문을 읽을 때 쓴다. */
	public String body() throws InterruptedException {
		awaitClosed(Duration.ofSeconds(5));
		synchronized (rawLines) {
			return String.join("\n", rawLines);
		}
	}

	/** 빈 신호를 건너뛴 다음 이벤트. 시간 안에 없으면 null이다. */
	public Event next(Duration timeout) throws InterruptedException {
		long until = System.nanoTime() + timeout.toNanos();
		while (true) {
			long left = until - System.nanoTime();
			if (left <= 0) {
				return null;
			}
			Event event = events.poll(left, TimeUnit.NANOSECONDS);
			if (event == null) {
				return null;
			}
			if (!event.comment()) {
				return event;
			}
		}
	}

	/** 빈 신호를 포함한 다음 묶음. 시간 안에 없으면 null이다. */
	public Event nextIncludingComments(Duration timeout) throws InterruptedException {
		return events.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
	}

	/** 서버가 연결을 닫을 때까지 기다린다. 닫혔으면 true다. */
	public boolean awaitClosed(Duration timeout) throws InterruptedException {
		return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Override
	public void close() {
		lines.close();
		http.shutdownNow();
	}

	private void read() {
		String id = null;
		String event = null;
		StringBuilder data = null;
		String retry = null;
		boolean comment = false;
		try {
			Iterator<String> iterator = lines.iterator();
			while (iterator.hasNext()) {
				String line = iterator.next();
				synchronized (rawLines) {
					rawLines.add(line);
				}
				if (line.isEmpty()) {
					if (id != null || event != null || data != null || retry != null || comment) {
						events.add(new Event(id, event, data == null ? null : data.toString(), retry,
								comment && id == null && event == null && data == null && retry == null));
					}
					id = null;
					event = null;
					data = null;
					retry = null;
					comment = false;
					continue;
				}
				if (line.startsWith(":")) {
					comment = true;
					continue;
				}
				int colon = line.indexOf(':');
				String field = colon < 0 ? line : line.substring(0, colon);
				String value = colon < 0 ? "" : line.substring(colon + 1);
				if (value.startsWith(" ")) {
					value = value.substring(1);
				}
				switch (field) {
					case "id" -> id = value;
					case "event" -> event = value;
					case "retry" -> retry = value;
					case "data" -> data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
					default -> {
					}
				}
			}
		}
		catch (RuntimeException ex) {
			// 클라이언트를 닫거나 연결이 끊기면 읽기가 끝난다.
		}
		finally {
			closed.countDown();
		}
	}

}
