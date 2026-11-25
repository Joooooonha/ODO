package com.odo.common.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * 파일 저장소(Cloudflare R2). 08_PracticeVideo_spec.md의 "저장소".
 * 영상 파일은 앱 서버를 거치지 않는다. 브라우저가 서명된 주소로 직접 올리고 직접 받는다. 앱 서버는 주소를 발급하고, 올라온 객체를
 * 확인하고, 지운다. 저장소에 닿지 못하면 StorageUnavailableException(503)을 던진다.
 */
public interface ObjectStorage {

	/** 서명된 PUT 주소. 서명에 크기와 형식이 들어가, 다른 크기나 형식으로 보내면 저장소가 거부한다. 저장소에 닿지 않고 계산만 한다. */
	PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl);

	/** 서명된 GET 주소. Range 요청도 된다. 저장소에 닿지 않고 계산만 한다. */
	PresignedDownload presignDownload(String key, Duration ttl);

	/** 객체의 크기와 형식. 없으면 비어 있다. HEAD 요청 한 번이다. */
	Optional<StoredObject> find(String key);

	/** 객체를 지운다. 없는 객체를 지워도 성공한다. */
	void delete(String key);

}
