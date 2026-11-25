package com.odo.schedule.application.dto;

/** 일정 목록의 구분. 예정은 종료 전(진행 중 포함), 완료는 종료 시간이 지난 일정이다. */
public enum ScheduleListStatus {

	UPCOMING, DONE

}
