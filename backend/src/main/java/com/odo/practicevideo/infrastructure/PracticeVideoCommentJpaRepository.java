package com.odo.practicevideo.infrastructure;

import com.odo.practicevideo.domain.entity.PracticeVideoComment;
import com.odo.practicevideo.domain.repository.PracticeVideoCommentRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** PracticeVideoCommentRepository의 JPA 구현. */
interface PracticeVideoCommentJpaRepository
		extends JpaRepository<PracticeVideoComment, Long>, PracticeVideoCommentRepository {

}
