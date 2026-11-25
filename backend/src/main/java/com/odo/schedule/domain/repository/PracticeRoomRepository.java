package com.odo.schedule.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.odo.schedule.domain.entity.PracticeRoom;

public interface PracticeRoomRepository {

	PracticeRoom save(PracticeRoom room);

	/** 삭제한 연습실도 찾는다. 일정 상세는 삭제한 연습실의 정보도 보여 준다. */
	Optional<PracticeRoom> findById(Long id);

	List<PracticeRoom> findAllById(Iterable<Long> ids);

	/** 수정할 때 행을 FOR UPDATE로 잠그고 읽는다. 동시에 들어온 삭제가 수정에 덮이지 않게 한다. */
	Optional<PracticeRoom> lockById(long id);

	/** 팀의 연습실 목록. 삭제하지 않은 것만, 이름순. */
	List<PracticeRoom> findAllByTeamIdAndDeletedAtIsNullOrderByNameAscIdAsc(Long teamId);

	/** 삭제하지 않은 연습실만 삭제 표시를 한다. 바뀐 행 수를 돌려준다. 동시에 두 번 지우면 하나는 0이다. */
	int markDeleted(long id, Instant now);

}
