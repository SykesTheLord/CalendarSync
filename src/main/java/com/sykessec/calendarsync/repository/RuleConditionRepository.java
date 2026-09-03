package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.RuleCondition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleConditionRepository extends JpaRepository<RuleCondition, Long> {

    List<RuleCondition> findAllByRuleId(Long ruleId);

    void deleteAllByRuleId(Long ruleId);
}
