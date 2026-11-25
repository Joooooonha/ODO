package com.odo.schedule.infrastructure;

import java.time.Instant;
import java.util.Optional;

import com.odo.schedule.domain.entity.PracticeRoom;
import com.odo.schedule.domain.repository.PracticeRoomRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PracticeRoomRepository의 JPA 구현. 잠금은 어떤 잠금이 걸리는지 보이도록 네이티브 쿼리로 쓴다.
 */
interface PracticeRoomJpaRepository extends JpaRepository<PracticeRoom, Long>, PracticeRoomRepository {

	@Override
	@Query(value = "SELECT * FROM practice_room WHERE id = :id FOR UPDATE", nativeQuery = true)
	Optional<PracticeRoom> lockById(@Param("id") long id);

	/** 영속성 컨텍스트를 거치지 않고 바로 UPDATE한다. 읽어 둔 엔티티의 deletedAt은 바뀌지 않는다. */
	@Override
	@Modifying
	@Query("update PracticeRoom r set r.deletedAt = :now, r.updatedAt = :now where r.id = :id and r.deletedAt is null")
	int markDeleted(@Param("id") long id, @Param("now") Instant now);

}
