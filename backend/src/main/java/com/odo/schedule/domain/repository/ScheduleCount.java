package com.odo.schedule.domain.repository;

/** 일정별로 센 값. 목록의 참여 인원을 한 번에 셀 때 쓴다. */
public interface ScheduleCount {

	Long getScheduleId();

	long getCount();

}
