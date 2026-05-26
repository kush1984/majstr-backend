package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateQuestionRepository extends JpaRepository<EstimateQuestion, UUID> {

    List<EstimateQuestion> findByEstimateIdOrderByCreatedAtAsc(UUID estimateId);
}
