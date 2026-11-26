package com.odo.practicevideo.infrastructure;

import com.odo.practicevideo.domain.entity.PracticeVideoThread;
import com.odo.practicevideo.domain.repository.PracticeVideoThreadRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** PracticeVideoThreadRepository의 JPA 구현. */
interface PracticeVideoThreadJpaRepository
		extends JpaRepository<PracticeVideoThread, Long>, PracticeVideoThreadRepository {

}
