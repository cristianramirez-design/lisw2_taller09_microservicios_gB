package co.edu.unicauca.review.repository;

import co.edu.unicauca.review.model.AssignmentStatus;
import co.edu.unicauca.review.model.ReviewAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ReviewAssignmentRepository extends JpaRepository<ReviewAssignment, Long> {
    List<ReviewAssignment> findByQuestionId(Long questionId);

    boolean existsByQuestionIdAndStatusIn(Long questionId, Collection<AssignmentStatus> statuses);
}
