package com.odo.practicevideo.domain.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.odo.common.text.UserTextRule;
import com.odo.practicevideo.domain.exception.InvalidPracticeVideoDescriptionException;
import com.odo.practicevideo.domain.exception.InvalidPracticeVideoFileSizeException;
import com.odo.practicevideo.domain.exception.InvalidPracticeVideoTitleException;
import com.odo.practicevideo.domain.exception.UnsupportedPracticeVideoTypeException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 연습 영상. 08_PracticeVideo_spec.md의 "업로드", "영상 상태", "데이터".
 * 객체 키(practice-videos/{id}/{UUID})에 ID가 들어가므로 ID는 저장소에서 미리 받아 만들 때 넣는다. 그래서 새 행인지를
 * Persistable로 알린다. 그러지 않으면 Spring Data가 저장할 때 먼저 SELECT한다.
 * 입력 규칙은 검증한 뒤 저장할 값을 돌려주는 public static 메서드로 둔다. 상태 바꾸기(완료, 삭제)는 조건부 UPDATE(저장소)로 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "practice_video")
public class PracticeVideo implements Persistable<Long> {

	/** 제목은 앞뒤 공백을 뺀 뒤 코드 포인트로 1~100자. */
	private static final int TITLE_MAX_LENGTH = 100;

	/** 설명은 앞뒤 공백을 뺀 뒤 코드 포인트로 100자까지. */
	private static final int DESCRIPTION_MAX_LENGTH = 100;

	/** 파일 크기 상한 1GB. 1차 규칙이라 설정으로 두지 않는다. */
	public static final long MAX_FILE_SIZE = 1_073_741_824L;

	/** 휴대폰이 찍는 형식. 1차는 원본 그대로 재생한다. */
	private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("video/mp4", "video/quicktime");

	private static final String OBJECT_KEY_PREFIX = "practice-videos/";

	@Id
	private Long id;

	@Column(name = "performance_id", nullable = false)
	private Long performanceId;

	@Column(name = "song_id", nullable = false)
	private Long songId;

	@Column(name = "uploader_account_id", nullable = false)
	private Long uploaderAccountId;

	@Column(nullable = false, length = TITLE_MAX_LENGTH)
	private String title;

	@Column(length = DESCRIPTION_MAX_LENGTH)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PracticeVideoStatus status;

	@Column(name = "object_key", nullable = false, length = 200)
	private String objectKey;

	@Column(name = "content_type", nullable = false, length = 30)
	private String contentType;

	@Column(name = "file_size", nullable = false)
	private long fileSize;

	@Column(name = "play_count", nullable = false)
	private long playCount;

	@Column(name = "upload_deadline", nullable = false)
	private Instant uploadDeadline;

	/** 업로드를 시작한 시각. 목록 정렬과 날짜 검색의 "업로드 시각"이다. */
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "ready_at")
	private Instant readyAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Transient
	private boolean isNew;

	private PracticeVideo(long id, long performanceId, long songId, long uploaderAccountId, String title,
			String description, long fileSize, String contentType, Instant now, Duration uploadDeadline) {
		this.id = id;
		this.performanceId = performanceId;
		this.songId = songId;
		this.uploaderAccountId = uploaderAccountId;
		this.title = title;
		this.description = description;
		this.status = PracticeVideoStatus.UPLOADING;
		this.objectKey = OBJECT_KEY_PREFIX + id + "/" + UUID.randomUUID();
		this.contentType = contentType;
		this.fileSize = fileSize;
		this.playCount = 0;
		this.uploadDeadline = now.plus(uploadDeadline);
		this.createdAt = now;
		this.updatedAt = now;
		this.isNew = true;
	}

	/**
	 * 업로드를 시작한 영상(UPLOADING). 제목, 설명, 크기, 형식의 순서로 확인하고 규칙에 맞지 않으면 각자의 예외를 던진다.
	 * 권한과 곡은 서비스가 먼저 확인한다.
	 */
	public static PracticeVideo startUpload(long id, long performanceId, long songId, long uploaderAccountId,
			String title, String description, long fileSize, String contentType, Instant now,
			Duration uploadDeadline) {
		String validTitle = requireValidTitle(title);
		String validDescription = requireValidDescription(description);
		long validSize = requireValidFileSize(fileSize);
		String validType = requireSupportedContentType(contentType);
		return new PracticeVideo(id, performanceId, songId, uploaderAccountId, validTitle, validDescription,
				validSize, validType, now, uploadDeadline);
	}

	/** 한 줄 규칙, 앞뒤 공백을 뺀 뒤 1~100자. 저장할 값을 돌려준다. 아니면 InvalidPracticeVideoTitleException. */
	public static String requireValidTitle(String title) {
		String normalized = UserTextRule.normalize(title);
		if (normalized == null || normalized.isEmpty() || codePoints(normalized) > TITLE_MAX_LENGTH
				|| UserTextRule.containsForbiddenCharForSingleLine(normalized)) {
			throw new InvalidPracticeVideoTitleException();
		}
		return normalized;
	}

	/**
	 * 여러 줄 규칙, 100자까지. 앞뒤 공백을 뺀 뒤 비어 있으면 설명 없음(null)이다. 저장할 값을 돌려준다.
	 * 아니면 InvalidPracticeVideoDescriptionException.
	 */
	public static String requireValidDescription(String description) {
		String normalized = UserTextRule.normalize(description);
		if (normalized == null || normalized.isEmpty()) {
			return null;
		}
		if (codePoints(normalized) > DESCRIPTION_MAX_LENGTH
				|| UserTextRule.containsForbiddenCharForMultiLine(normalized)) {
			throw new InvalidPracticeVideoDescriptionException();
		}
		return normalized;
	}

	/** 1바이트부터 1GB까지. 아니면 InvalidPracticeVideoFileSizeException. */
	public static long requireValidFileSize(long fileSize) {
		if (fileSize < 1 || fileSize > MAX_FILE_SIZE) {
			throw new InvalidPracticeVideoFileSizeException();
		}
		return fileSize;
	}

	/** video/mp4와 video/quicktime만. 대소문자나 매개변수(codecs 등)가 다른 값도 받지 않는다. 아니면 UnsupportedPracticeVideoTypeException. */
	public static String requireSupportedContentType(String contentType) {
		if (contentType == null || !SUPPORTED_CONTENT_TYPES.contains(contentType)) {
			throw new UnsupportedPracticeVideoTypeException();
		}
		return contentType;
	}

	/** 제목과 설명을 바꾼다. 규칙에 맞지 않으면 각자의 예외. 행을 잠근 뒤 부른다. */
	public void changeInfo(String title, String description, Instant now) {
		String validTitle = requireValidTitle(title);
		String validDescription = requireValidDescription(description);
		this.title = validTitle;
		this.description = validDescription;
		this.updatedAt = now;
	}

	public boolean isUploadedBy(long accountId) {
		return uploaderAccountId == accountId;
	}

	public boolean isUploading() {
		return status == PracticeVideoStatus.UPLOADING;
	}

	public boolean isReady() {
		return status == PracticeVideoStatus.READY;
	}

	public boolean isDeleting() {
		return status == PracticeVideoStatus.DELETING;
	}

	/** 완료 기한이 지났는지. 기한과 같은 시각부터 지난 것이다. */
	public boolean isUploadExpired(Instant now) {
		return !uploadDeadline.isAfter(now);
	}

	/** 올라온 객체가 시작할 때 서명한 크기와 형식과 같은지. */
	public boolean matchesUploadedFile(long size, String contentType) {
		return fileSize == size && this.contentType.equals(contentType);
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	private static int codePoints(String text) {
		return text.codePointCount(0, text.length());
	}

}
