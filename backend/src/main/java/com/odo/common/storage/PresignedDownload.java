package com.odo.common.storage;

import java.time.Instant;

/** 서명된 내려받기(재생) 주소와 만료 시각. */
public record PresignedDownload(String url, Instant expiresAt) {

}
