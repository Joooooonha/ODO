package com.odo.performance.infrastructure;

import java.time.Instant;

import com.odo.performance.domain.entity.Song;
import com.odo.performance.domain.repository.SongRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * SongRepository의 JPA 구현. Spring Data가 메서드 이름으로 쿼리를 만든다.
 * 조건부 UPDATE는 영속성 컨텍스트를 거치지 않고 DB에 바로 반영한다. 이미 읽어 둔 Song 엔티티의 값은 바뀌지 않는다.
 * 동시에 두 트랜잭션이 같은 행을 바꾸면 뒤의 것은 앞의 것이 끝날 때까지 기다린 뒤 조건을 다시 보고 0행이 된다.
 */
interface SongJpaRepository extends JpaRepository<Song, Long>, SongRepository {

	@Override
	@Modifying
	@Query("update Song s set s.name = :name, s.updatedAt = :updatedAt where s.id = :id and s.deletedAt is null")
	int renameIfNotDeleted(@Param("id") Long id, @Param("name") String name, @Param("updatedAt") Instant updatedAt);

	@Override
	@Modifying
	@Query("update Song s set s.deletedAt = :deletedAt, s.updatedAt = :deletedAt where s.id = :id and s.deletedAt is null")
	int deleteIfNotDeleted(@Param("id") Long id, @Param("deletedAt") Instant deletedAt);

}
