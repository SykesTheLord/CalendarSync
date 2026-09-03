package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.DeletionRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeletionRuleRepository extends JpaRepository<DeletionRule, Long> {

    List<DeletionRule> findAllByUserIdOrderByPriorityAsc(Long userId);

    Optional<DeletionRule> findByIdAndUserId(Long id, Long userId);

    void deleteByIdAndUserId(Long id, Long userId);
}
