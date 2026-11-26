package com.odo.practicevideo.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.odo.practicevideo.domain.exception.InvalidCursorException;

/**
 * 목록 커서. 마지막 항목의 (업로드 시각, ID)를 {@code {epoch µs}:{id}}로 적어 URL 안전 base64(패딩 없음)로 감싼다.
 * 08_PracticeVideo_spec.md의 "목록과 날짜 검색". 클라이언트는 해석하지 않고 그대로 돌려보낸다.
 */
public record PracticeVideoCursor(Instant uploadedAt, long id) {

	private static final long MICROS_PER_SECOND = 1_000_000L;

	public static String encode(Instant uploadedAt, long id) {
		long micros = Math.addExact(Math.multiplyExact(uploadedAt.getEpochSecond(), MICROS_PER_SECOND),
				uploadedAt.getNano() / 1_000);
		String raw = micros + ":" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	/** 읽지 못하면 InvalidCursorException. */
	public static PracticeVideoCursor decode(String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = raw.split(":", -1);
			if (parts.length != 2) {
				throw new InvalidCursorException();
			}
			long micros = Long.parseLong(parts[0]);
			long id = Long.parseLong(parts[1]);
			Instant uploadedAt = Instant.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
					Math.floorMod(micros, MICROS_PER_SECOND) * 1_000);
			return new PracticeVideoCursor(uploadedAt, id);
		}
		catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException ex) {
			// base64가 아님, 숫자가 아님(NumberFormatException도 IllegalArgumentException이다), 시각의 범위를 넘음
			throw new InvalidCursorException();
		}
	}

}
