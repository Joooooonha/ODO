package SeSAC.Dance_Assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * 테스트용 STOMP 클라이언트. 웹소켓 위에서 프레임을 직접 주고받는다 — 대기열 상한, "메시지 하나에 프레임
 * 하나" 규칙, 갑작스러운 연결 끊김을 정확히 재현하려면 라이브러리 클라이언트보다 이쪽이 낫다.
 *
 * <p>브라우저처럼 핸드셰이크에 로그인 쿠키와 Origin을 싣는다.
 */
public final class RawStompClient implements AutoCloseable {

    public static final String ORIGIN = "http://localhost:5173";

    private final ObjectMapper om = new ObjectMapper();
    /** 받은 프레임(도착 순서). 꺼낸 것은 지운다. 이 리스트 자체가 잠금이다. */
    private final List<Frame> frames = new ArrayList<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private final AtomicInteger subscriptionIds = new AtomicInteger();
    private WebSocketSession session;

    public record Frame(String command, Map<String, String> headers, String body) {
        public String destination() {
            return headers.get("destination");
        }

        public JsonNode json(ObjectMapper om) {
            try {
                return om.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException(body, e);
            }
        }
    }

    /** 핸드셰이크만. 실패하면 예외(메시지에 HTTP 상태가 들어 있다). */
    public static RawStompClient open(int port, Cookie cookie, String origin) throws Exception {
        RawStompClient client = new RawStompClient();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (cookie != null) {
            headers.add("Cookie", cookie.getName() + "=" + cookie.getValue());
        }
        if (origin != null) {
            headers.setOrigin(origin);
        }
        client.session = new StandardWebSocketClient()
                .execute(client.new Handler(), headers, URI.create("ws://localhost:" + port + "/api/ws"))
                .get(10, TimeUnit.SECONDS);
        return client;
    }

    /** 핸드셰이크 + CONNECT + 개인 목적지 두 개 구독. 하트비트는 쓰지 않는다(0,0). */
    public static RawStompClient connect(int port, Cookie cookie) throws Exception {
        return connect(port, cookie, "0,0");
    }

    public static RawStompClient connect(int port, Cookie cookie, String heartBeat) throws Exception {
        RawStompClient client = open(port, cookie, ORIGIN);
        client.sendFrame("CONNECT", Map.of("accept-version", "1.2", "host", "localhost", "heart-beat", heartBeat), "");
        client.expect("CONNECTED", Duration.ofSeconds(10));
        client.subscribe("/user/queue/acks");
        client.subscribe("/user/queue/events");
        client.awaitSubscriptionsProcessed();
        return client;
    }

    /**
     * 앞서 보낸 SUBSCRIBE를 서버가 다 처리할 때까지 기다린다. 메모리 브로커는 SUBSCRIBE에 RECEIPT를 주지
     * 않는다(DISCONNECT에만 — spring-websocket 확인). 대신 같은 세션의 메시지는 하나씩 처리되고, 앞 메시지를
     * 브로커까지 모든 처리기가 끝내야 다음이 나간다. 그래서 일부러 읽을 수 없는 SEND를 보내 그 오류 응답이
     * 오면 앞의 구독은 등록된 것이다. 이게 없으면 느린 CI에서 연결 직후의 개인 알림(GRANTED 등)이 구독
     * 등록 전에 나가 버려진다.
     */
    public void awaitSubscriptionsProcessed() throws IOException, InterruptedException {
        sendRaw(0, "sync");
        expect(f -> "MESSAGE".equals(f.command()) && f.destination() != null
                && f.destination().endsWith("/queue/acks") && f.body().contains("INVALID_MESSAGE"),
                Duration.ofSeconds(10));
    }

    public String subscribe(String destination) throws IOException {
        String id = "sub-" + subscriptionIds.incrementAndGet();
        sendFrame("SUBSCRIBE", Map.of("id", id, "destination", destination), "");
        return id;
    }

    public void unsubscribe(String id) throws IOException {
        sendFrame("UNSUBSCRIBE", Map.of("id", id), "");
    }

    public void sendMessage(long roomId, String clientMessageId, String content, boolean resume) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientMessageId", clientMessageId);
        body.put("content", content);
        if (resume) {
            body.put("resume", true);
        }
        sendRaw(roomId, om.writeValueAsString(body));
    }

    public void sendRaw(long roomId, String body) throws IOException {
        sendFrame("SEND", Map.of("destination", "/app/rooms/" + roomId + "/messages",
                "content-type", "application/json"), body);
    }

    public synchronized void sendFrame(String command, Map<String, String> headers, String body) throws IOException {
        sendText(encode(command, headers, body));
    }

    /** 프레임 규칙을 일부러 어길 때. */
    public synchronized void sendText(String text) throws IOException {
        session.sendMessage(new TextMessage(text));
    }

    public static String encode(String command, Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder(command).append('\n');
        headers.forEach((k, v) -> sb.append(k).append(':').append(v).append('\n'));
        return sb.append('\n').append(body).append('\0').toString();
    }

    /** 조건에 맞는 가장 앞의 프레임을 꺼낸다. 다른 프레임은 순서 그대로 남겨 둔다. */
    public Frame expect(Predicate<Frame> match, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (frames) {
            while (true) {
                for (int i = 0; i < frames.size(); i++) {
                    if (match.test(frames.get(i))) {
                        return frames.remove(i);
                    }
                }
                long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (leftMs <= 0) {
                    throw new AssertionError("기다린 프레임이 오지 않음. 받은 것: " + frames);
                }
                frames.wait(leftMs);
            }
        }
    }

    public Frame expect(String command, Duration timeout) throws InterruptedException {
        return expect(f -> f.command().equals(command), timeout);
    }

    /** 개인 목적지(acks)의 다음 프레임 본문. */
    public JsonNode nextAck(Duration timeout) throws InterruptedException {
        return expect(f -> "MESSAGE".equals(f.command()) && f.destination() != null
                && f.destination().endsWith("/queue/acks"), timeout).json(om);
    }

    public JsonNode nextEvent(Duration timeout) throws InterruptedException {
        return expect(f -> "MESSAGE".equals(f.command()) && f.destination() != null
                && f.destination().endsWith("/queue/events"), timeout).json(om);
    }

    public JsonNode nextRoomMessage(long roomId, Duration timeout) throws InterruptedException {
        return expect(f -> "MESSAGE".equals(f.command()) && ("/topic/rooms." + roomId).equals(f.destination()),
                timeout).json(om);
    }

    /** 일정 시간 동안 조건에 맞는 프레임이 오지 않아야 한다. */
    public void expectNone(Predicate<Frame> match, Duration during) throws InterruptedException {
        try {
            Frame f = expect(match, during);
            throw new IllegalStateException("오면 안 되는 프레임: " + f);
        } catch (AssertionError expected) {
            // 오지 않았다
        }
    }

    public List<Frame> received() {
        synchronized (frames) {
            return List.copyOf(frames);
        }
    }

    public CloseStatus awaitClosed(Duration timeout) throws Exception {
        return closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isOpen() {
        return session.isOpen() && !closed.isDone();
    }

    /** 브라우저 탭을 닫거나 네트워크가 끊긴 것처럼 바로 끊는다(DISCONNECT 프레임 없이). */
    public void drop() throws IOException {
        session.close(CloseStatus.GOING_AWAY);
    }

    @Override
    public void close() throws IOException {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }

    private final class Handler extends TextWebSocketHandler {

        private final AtomicReference<StringBuilder> partial = new AtomicReference<>(new StringBuilder());

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage message) {
            StringBuilder buffer = partial.get();
            buffer.append(message.getPayload());
            int nul;
            while ((nul = buffer.indexOf("\0")) >= 0) {
                String raw = buffer.substring(0, nul);
                buffer.delete(0, nul + 1);
                Frame frame = parse(raw);
                if (frame != null) {
                    synchronized (frames) {
                        frames.add(frame);
                        frames.notifyAll();
                    }
                }
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            closed.complete(status);
        }

        private Frame parse(String raw) {
            String text = raw.replaceFirst("^[\\r\\n]+", "");
            if (text.isEmpty()) {
                return null;   // 하트비트
            }
            int headerEnd = text.indexOf("\n\n");
            String head = headerEnd < 0 ? text : text.substring(0, headerEnd);
            String body = headerEnd < 0 ? "" : text.substring(headerEnd + 2);
            String[] lines = head.split("\n");
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.putIfAbsent(lines[i].substring(0, colon), lines[i].substring(colon + 1));
                }
            }
            return new Frame(lines[0].trim(), headers, body);
        }
    }
}
