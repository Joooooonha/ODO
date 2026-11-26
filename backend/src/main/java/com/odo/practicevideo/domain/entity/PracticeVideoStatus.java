package com.odo.practicevideo.domain.entity;

/** 연습 영상의 상태. 08_PracticeVideo_spec.md의 "영상 상태". */
public enum PracticeVideoStatus {

	/** 업로드를 시작했고 완료 확인 전. 올린 디렉터의 목록에만 보인다. */
	UPLOADING,

	/** 완료 확인을 마쳤다. 명단 전원에게 보인다. */
	READY,

	/** 올린 디렉터가 삭제했고 R2 객체를 지우기 전. 아무에게도 보이지 않는다. */
	DELETING

}
