package com.odo.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.odo.common.storage.ObjectStorage;
import com.odo.common.storage.PresignedDownload;
import com.odo.common.storage.PresignedUpload;
import com.odo.common.storage.StorageUnavailableException;
import com.odo.common.storage.StoredObject;

/**
 * 메모리에 객체를 두는 가짜 저장소. 08_PracticeVideo_spec.md의 "저장소".
 * 서명된 주소는 {@code https://fake-storage.test/{key}?...} 형태다. 브라우저가 R2에 올리는 것은 uploadAsBrowser로 흉내 낸다.
 * 다음 호출을 실패하게 만들 수 있다(R2에 닿지 못한 경우).
 */
public class InMemoryObjectStorage implements ObjectStorage {

	public static final String BASE_URL = "https://fake-storage.test/";

	private final Clock clock;

	/** 업로드 주소를 발급한 키와 그때 서명한 크기와 형식. */
	private final Map<String, StoredObject> presigned = new ConcurrentHashMap<>();

	private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();

	private final Set<String> deletedKeys = ConcurrentHashMap.newKeySet();

	private final AtomicBoolean failFind = new AtomicBoolean();

	/** 삭제가 실패할 키. 한 번 실패하면 목록에서 빠진다. */
	private final Set<String> failDeleteOnce = ConcurrentHashMap.newKeySet();

	private final AtomicBoolean failAllDeletes = new AtomicBoolean();

	public InMemoryObjectStorage(Clock clock) {
		this.clock = clock;
	}

	public void reset() {
		presigned.clear();
		objects.clear();
		deletedKeys.clear();
		failFind.set(false);
		failDeleteOnce.clear();
		failAllDeletes.set(false);
	}

	@Override
	public PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl) {
		presigned.put(key, new StoredObject(contentLength, contentType));
		Instant expiresAt = clock.instant().plus(ttl);
		return new PresignedUpload(BASE_URL + key + "?op=put&expires=" + expiresAt.getEpochSecond(),
				Map.of("Content-Type", contentType), expiresAt);
	}

	@Override
	public PresignedDownload presignDownload(String key, Duration ttl) {
		Instant expiresAt = clock.instant().plus(ttl);
		return new PresignedDownload(BASE_URL + key + "?op=get&expires=" + expiresAt.getEpochSecond(), expiresAt);
	}

	@Override
	public Optional<StoredObject> find(String key) {
		if (failFind.get()) {
			throw new StorageUnavailableException("가짜 저장소 실패", null);
		}
		return Optional.ofNullable(objects.get(key));
	}

	@Override
	public void delete(String key) {
		if (failAllDeletes.get() || failDeleteOnce.remove(key)) {
			throw new StorageUnavailableException("가짜 저장소 실패", null);
		}
		objects.remove(key);
		deletedKeys.add(key);
	}

	/** 브라우저가 업로드 주소로 서명한 크기와 형식 그대로 올린 것처럼 객체를 둔다. 그 키를 돌려준다. */
	public String uploadAsBrowser(String uploadUrl) {
		String key = keyOf(uploadUrl);
		StoredObject signed = presigned.get(key);
		if (signed == null) {
			throw new IllegalArgumentException("발급하지 않은 업로드 주소: " + uploadUrl);
		}
		objects.put(key, signed);
		return key;
	}

	/** 서명과 다른 크기나 형식으로 객체를 둔다(R2가 거부하지 못한 경우를 흉내 낸다). */
	public String uploadAsBrowser(String uploadUrl, long size, String contentType) {
		String key = keyOf(uploadUrl);
		objects.put(key, new StoredObject(size, contentType));
		return key;
	}

	public boolean contains(String key) {
		return objects.containsKey(key);
	}

	public boolean wasDeleted(String key) {
		return deletedKeys.contains(key);
	}

	public void failFind(boolean fail) {
		failFind.set(fail);
	}

	public void failDeleteOnce(String key) {
		failDeleteOnce.add(key);
	}

	public void failAllDeletes(boolean fail) {
		failAllDeletes.set(fail);
	}

	public static String keyOf(String url) {
		if (!url.startsWith(BASE_URL)) {
			throw new IllegalArgumentException("가짜 저장소의 주소가 아니다: " + url);
		}
		String rest = url.substring(BASE_URL.length());
		int query = rest.indexOf('?');
		return query < 0 ? rest : rest.substring(0, query);
	}

}
