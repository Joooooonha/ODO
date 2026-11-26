package com.odo.practicevideo.application.dto;

import java.util.List;

/** 목록 한 페이지. nextCursor는 다음 페이지가 없으면 null이다. */
public record PracticeVideoPageResult(List<PracticeVideoResult> items, String nextCursor) {

}
