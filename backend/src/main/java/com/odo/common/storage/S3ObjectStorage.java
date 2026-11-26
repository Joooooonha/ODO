package com.odo.common.storage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * R2의 S3 호환 API를 AWS SDK for Java v2로 쓰는 저장소. 08_PracticeVideo_spec.md의 "저장소".
 * <ul>
 * <li>지역은 auto다(R2의 값).</li>
 * <li>주소는 경로 방식(https://{endpoint}/{bucket}/{key})으로 만든다. R2와 S3 호환 테스트 컨테이너가 모두 받는다.</li>
 * <li>최근 SDK는 요청에 체크섬 헤더를 기본으로 붙인다. R2와 맞추려고 필요할 때만 붙이게 한다.</li>
 * <li>호출마다 5초 시간 제한을 둔다. 넘으면 StorageUnavailableException이다.</li>
 * </ul>
 * 만료 시각은 앱의 Clock으로 계산해 돌려준다. 서명 자체는 SDK가 시스템 시계로 한다.
 */
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {

	private static final Region REGION = Region.of("auto");

	private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

	private final String bucket;

	private final Clock clock;

	private final S3Client client;

	private final S3Presigner presigner;

	public S3ObjectStorage(StorageProperties properties, Clock clock) {
		this.bucket = properties.bucket();
		this.clock = clock;
		URI endpoint = URI.create(properties.endpoint());
		StaticCredentialsProvider credentials = StaticCredentialsProvider
			.create(AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
		S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
		this.client = S3Client.builder()
			.endpointOverride(endpoint)
			.region(REGION)
			.credentialsProvider(credentials)
			.serviceConfiguration(pathStyle)
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
			.overrideConfiguration(config -> config.apiCallTimeout(CALL_TIMEOUT))
			.build();
		this.presigner = S3Presigner.builder()
			.endpointOverride(endpoint)
			.region(REGION)
			.credentialsProvider(credentials)
			.serviceConfiguration(pathStyle)
			.build();
	}

	@Override
	public PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl) {
		PutObjectRequest put = PutObjectRequest.builder()
			.bucket(bucket)
			.key(key)
			.contentType(contentType)
			.contentLength(contentLength)
			.build();
		String url = presigner
			.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put).build())
			.url()
			.toString();
		return new PresignedUpload(url, Map.of("Content-Type", contentType), clock.instant().plus(ttl));
	}

	@Override
	public PresignedDownload presignDownload(String key, Duration ttl) {
		GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
		String url = presigner
			.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build())
			.url()
			.toString();
		return new PresignedDownload(url, clock.instant().plus(ttl));
	}

	@Override
	public Optional<StoredObject> find(String key) {
		try {
			HeadObjectResponse head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
			return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				return Optional.empty();
			}
			throw unavailableIfServerError(ex);
		}
		catch (SdkClientException ex) {
			throw new StorageUnavailableException("저장소에 닿지 못했다", ex);
		}
	}

	@Override
	public void delete(String key) {
		try {
			client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
		}
		catch (S3Exception ex) {
			// S3는 없는 객체를 지워도 204다. 404를 주는 구현도 있으므로 같은 성공으로 본다.
			if (ex.statusCode() != 404) {
				throw unavailableIfServerError(ex);
			}
		}
		catch (SdkClientException ex) {
			throw new StorageUnavailableException("저장소에 닿지 못했다", ex);
		}
	}

	@Override
	public void close() {
		presigner.close();
		client.close();
	}

	/** 5xx는 잠시 뒤 다시 보내면 되는 경우다. 나머지(권한 오류 등)는 설정 문제라 그대로 던져 500이 되게 한다. */
	private static RuntimeException unavailableIfServerError(S3Exception ex) {
		if (ex.statusCode() >= 500) {
			return new StorageUnavailableException("저장소가 오류로 답했다: " + ex.statusCode(), ex);
		}
		return ex;
	}

}
