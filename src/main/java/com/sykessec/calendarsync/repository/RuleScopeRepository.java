package com.sykessec.calendarsync.repository;

import com.sykessec.calendarsync.entity.RuleScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleScopeRepository extends JpaRepository<RuleScope, Long> {

    List<RuleScope> findAllByRuleId(Long ruleId);

    /** Used by the sync job to find which rules apply to a given calendar. */
    List<RuleScope> findAllByCalendarId(Long calendarId);

    /** Used by IcsExportService to find which rules apply to a given published feed. */
    List<RuleScope> findAllByPublishedFeedId(Long publishedFeedId);

    void deleteAllByRuleId(Long ruleId);
}
