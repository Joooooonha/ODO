package com.odo.schedule.application.dto;

import java.time.Instant;

import com.odo.schedule.domain.entity.PracticeRoom;

/** 연습실 정보. 비밀번호는 평문이다. 목록 응답은 비밀번호와 메모를 빼고 내보낸다. */
public record PracticeRoomResult(
		long id,
		long teamId,
		String name,
		String address,
		String password,
		Integer rentalFee,
		String memo,
		boolean deleted,
		Instant createdAt,
		Instant updatedAt) {

	public static PracticeRoomResult of(PracticeRoom room) {
		return new PracticeRoomResult(room.getId(), room.getTeamId(), room.getName(), room.getAddress(),
				room.getPassword(), room.getRentalFee(), room.getMemo(), room.isDeleted(), room.getCreatedAt(),
				room.getUpdatedAt());
	}

}
