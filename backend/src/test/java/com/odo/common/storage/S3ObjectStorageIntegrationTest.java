package com.odo.common.storage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R2 구현(S3ObjectStorage)이 S3 API를 제대로 부르는지. 08_PracticeVideo_spec.md의 "저장소 구현 (통합)".
 * S3 호환 저장소 컨테이너(Adobe S3Mock)에 붙인다. S3Mock은 서명을 검사하지 않으므로, 서명된 크기와 형식으로 거부되는지는
 * 배포 뒤 R2에서 직접 확인한다.
 */
@Testcontainers
class S3ObjectStorageIntegrationTest {

	private static final String BUCKET = "test-bucket";

	@Container
	static final GenericContainer<?> S3_MOCK = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3"))
		.withExposedPorts(9090)
		.withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
		.waitingFor(Wait.forListeningPort());

	private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

	private final HttpClient http = HttpClient.newHttpClient();

	private S3ObjectStorage storage;

	private String key;

	@BeforeEach
	void setUp() {
		String endpoint = "http://" + S3_MOCK.getHost() + ":" + S3_MOCK.getMappedPort(9090);
		storage = new S3ObjectStorage(new StorageProperties(endpoint, BUCKET, "test-access-key", "test-secret-key"),
				Clock.fixed(NOW, ZoneOffset.UTC));
		key = "practice-videos/1/" + UUID.randomUUID();
	}

	private HttpResponse<String> put(PresignedUpload upload, byte[] body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.url()))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
		upload.headers().forEach(request::header);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	@DisplayName("서명된 PUT 주소로 서명한 크기와 형식의 파일을 올리면 성공하고, find가 그 크기와 형식을 돌려준다")
	void uploadAndFind() throws Exception {
		byte[] body = "practice-video-bytes".getBytes(StandardCharsets.UTF_8);

		PresignedUpload upload = storage.presignUpload(key, "video/mp4", body.length, Duration.ofMinutes(30));

		assertThat(upload.headers()).containsEntry("Content-Type", "video/mp4");
		assertThat(upload.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
		assertThat(put(upload, body).statusCode()).isEqualTo(200);
		assertThat(storage.find(key)).contains(new StoredObject(body.length, "video/mp4"));
	}

	@Test
	@DisplayName("없는 객체는 find가 비어 있다")
	void findMissing() {
		assertThat(storage.find(key)).isEmpty();
	}

	@Test
	@DisplayName("서명된 GET 주소로 Range 요청을 하면 그 구간만 온다")
	void rangeDownload() throws Exception {
		byte[] body = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
		put(storage.presignUpload(key, "video/mp4", body.length, Duration.ofMinutes(30)), body);

		PresignedDownload download = storage.presignDownload(key, Duration.ofHours(3));
		HttpResponse<String> response = http.send(
				HttpRequest.newBuilder(URI.create(download.url())).header("Range", "bytes=0-9").GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(download.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(3)));
		assertThat(response.statusCode()).isEqualTo(206);
		assertThat(response.body()).isEqualTo("0123456789");
	}

	@Test
	@DisplayName("delete는 객체를 지우고, 없는 객체에도 성공한다")
	void delete() throws Exception {
		byte[] body = "x".getBytes(StandardCharsets.UTF_8);
		put(storage.presignUpload(key, "video/mp4", body.length, Duration.ofMinutes(30)), body);

		storage.delete(key);

		assertThat(storage.find(key)).isEmpty();
		assertThatCode(() -> storage.delete(key)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("저장소에 닿지 못하면 find와 delete가 StorageUnavailableException을 던진다")
	void unavailable() {
		S3ObjectStorage unreachable = new S3ObjectStorage(
				new StorageProperties("http://localhost:1", BUCKET, "test-access-key", "test-secret-key"),
				Clock.fixed(NOW, ZoneOffset.UTC));

		assertThatThrownBy(() -> unreachable.find(key)).isInstanceOf(StorageUnavailableException.class);
		assertThatThrownBy(() -> unreachable.delete(key)).isInstanceOf(StorageUnavailableException.class);
	}

}
