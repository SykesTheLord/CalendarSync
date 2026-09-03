package com.sykessec.calendarsync.service;

import com.sykessec.calendarsync.entity.CalendarEntity;
import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.PublishedFeed;
import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.RuleScope;
import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import com.sykessec.calendarsync.entity.enums.RuleField;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.ics.IcsExportService;
import com.sykessec.calendarsync.repository.CalendarRepository;
import com.sykessec.calendarsync.repository.DeletionRuleRepository;
import com.sykessec.calendarsync.repository.PublishedFeedRepository;
import com.sykessec.calendarsync.repository.RuleConditionRepository;
import com.sykessec.calendarsync.repository.RuleScopeRepository;
import com.sykessec.calendarsync.rules.RuleEngine;
import com.sykessec.calendarsync.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD for deletion_rule + rule_condition + rule_scope, plus the two safety
 * checks the spec requires from the first working version: field/operator
 * combinations are validated against the RuleEngine's per-field evaluators
 * (e.g. no REGEX on DURATION), and a DELETE rule can never be scoped
 * directly to a calendar with is_writable=0 - filtering an ICS_SOURCE only
 * ever affects its downstream published_feed, never the source.
 */
@Service
public class DeletionRuleService {

    private final DeletionRuleRepository ruleRepository;
    private final RuleConditionRepository conditionRepository;
    private final RuleScopeRepository scopeRepository;
    private final CalendarRepository calendarRepository;
    private final PublishedFeedRepository publishedFeedRepository;
    private final RuleEngine ruleEngine;
    private final IcsExportService icsExportService;
    private final CurrentUser currentUser;

    public DeletionRuleService(DeletionRuleRepository ruleRepository,
                                RuleConditionRepository conditionRepository,
                                RuleScopeRepository scopeRepository,
                                CalendarRepository calendarRepository,
                                PublishedFeedRepository publishedFeedRepository,
                                RuleEngine ruleEngine,
                                IcsExportService icsExportService,
                                CurrentUser currentUser) {
        this.ruleRepository = ruleRepository;
        this.conditionRepository = conditionRepository;
        this.scopeRepository = scopeRepository;
        this.calendarRepository = calendarRepository;
        this.publishedFeedRepository = publishedFeedRepository;
        this.ruleEngine = ruleEngine;
        this.icsExportService = icsExportService;
        this.currentUser = currentUser;
    }

    public List<DeletionRule> listForCurrentUser() {
        return ruleRepository.findAllByUserIdOrderByPriorityAsc(currentUser.id());
    }

    public List<RuleCondition> conditionsFor(Long ruleId) {
        return conditionRepository.findAllByRuleId(ruleId);
    }

    /**
     * Which operators the condition editor should offer for a field. Same
     * source of truth addCondition validates against, so the editor can't
     * offer a combination that will then be refused.
     */
    public java.util.Set<RuleOperator> supportedOperators(RuleField field) {
        return ruleEngine.supportedOperators(field);
    }

    /** New rules always default to DRY_RUN - see DeletionRule.action's default. */
    public DeletionRule create(String name, MatchLogic matchLogic, int priority) {
        DeletionRule rule = new DeletionRule();
        rule.setUserId(currentUser.id());
        rule.setName(name);
        rule.setMatchLogic(matchLogic);
        rule.setAction(RuleAction.DRY_RUN);
        rule.setPriority(priority);
        rule.setEnabled(true);
        return ruleRepository.save(rule);
    }

    @Transactional
    public void delete(Long ruleId) {
        // Ownership first. The rule delete itself is user-scoped, but the
        // condition and scope deletes take a bare rule id, so running them
        // before the check meant a foreign id still emptied that rule of its
        // conditions and scopes while leaving the rule row standing.
        ruleRepository.findByIdAndUserId(ruleId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your rule: " + ruleId));

        // Invalidate before the scopes go: invalidateForRule resolves the
        // affected feeds *through* rule_scope, so it finds nothing once the
        // scopes are gone and every feed this rule was filtering would serve
        // stale cached ICS.
        icsExportService.invalidateForRule(ruleId);
        conditionRepository.deleteAllByRuleId(ruleId);
        scopeRepository.deleteAllByRuleId(ruleId);
        ruleRepository.deleteByIdAndUserId(ruleId, currentUser.id());
    }

    public DeletionRule save(DeletionRule rule) {
        if (!rule.getUserId().equals(currentUser.id())) {
            throw new IllegalArgumentException("Cannot save a rule belonging to another user");
        }
        if (rule.getAction() == RuleAction.DELETE) {
            for (RuleScope scope : scopeRepository.findAllByRuleId(rule.getId())) {
                rejectIfNonWritableCalendarScope(scope);
            }
        }
        DeletionRule saved = ruleRepository.save(rule);
        icsExportService.invalidateForRule(saved.getId());
        return saved;
    }

    public RuleCondition addCondition(Long ruleId, RuleField field, RuleOperator operator,
                                       String value, boolean caseSensitive) {
        DeletionRule rule = ruleRepository.findByIdAndUserId(ruleId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your rule: " + ruleId));
        ruleEngine.validateCondition(field, operator, value);
        RuleCondition condition = new RuleCondition();
        condition.setRuleId(rule.getId());
        condition.setField(field);
        condition.setOperator(operator);
        condition.setValue(value);
        condition.setCaseSensitive(caseSensitive);
        RuleCondition saved = conditionRepository.save(condition);
        icsExportService.invalidateForRule(ruleId);
        return saved;
    }

    public void deleteCondition(Long conditionId) {
        RuleCondition condition = conditionRepository.findById(conditionId)
                .orElseThrow(() -> new IllegalArgumentException("No such condition: " + conditionId));
        ruleRepository.findByIdAndUserId(condition.getRuleId(), currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your rule"));
        conditionRepository.deleteById(conditionId);
        icsExportService.invalidateForRule(condition.getRuleId());
    }

    public List<RuleScope> scopesFor(Long ruleId) {
        return scopeRepository.findAllByRuleId(ruleId);
    }

    /**
     * Refuses (rather than silently no-op'ing) any attempt to scope a
     * DELETE rule directly against a calendar where is_writable=0.
     */
    public RuleScope addCalendarScope(Long ruleId, Long calendarId) {
        DeletionRule rule = ruleRepository.findByIdAndUserId(ruleId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your rule: " + ruleId));
        CalendarEntity calendar = calendarRepository.findByIdAndUserId(calendarId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your calendar: " + calendarId));

        if (rule.getAction() == RuleAction.DELETE && !calendar.isWritable()) {
            throw new IllegalArgumentException(
                    "Cannot scope a DELETE rule directly to a read-only calendar (\"" + calendar.getName()
                            + "\"). Filtering a read-only source only ever affects a published feed built from it.");
        }

        RuleScope scope = new RuleScope();
        scope.setRuleId(ruleId);
        scope.setCalendarId(calendarId);
        return scopeRepository.save(scope);
    }

    /** Scopes a rule to a published_feed - this is how filtering an ICS_SOURCE (or any feed) actually happens. */
    public RuleScope addFeedScope(Long ruleId, Long feedId) {
        ruleRepository.findByIdAndUserId(ruleId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your rule: " + ruleId));
        PublishedFeed feed = publishedFeedRepository.findByIdAndUserId(feedId, currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your feed: " + feedId));

        RuleScope scope = new RuleScope();
        scope.setRuleId(ruleId);
        scope.setPublishedFeedId(feedId);
        RuleScope saved = scopeRepository.save(scope);
        icsExportService.invalidate(feed.getId());
        return saved;
    }

    /**
     * Ownership is checked through the scope's rule before anything is
     * deleted, the same way deleteCondition does it. Without that this took a
     * bare id and deleted whatever it pointed at, including another user's.
     */
    public void removeScope(Long scopeId) {
        RuleScope scope = scopeRepository.findById(scopeId)
                .orElseThrow(() -> new IllegalArgumentException("No such scope: " + scopeId));
        ruleRepository.findByIdAndUserId(scope.getRuleId(), currentUser.id())
                .orElseThrow(() -> new IllegalArgumentException("Not your rule"));
        scopeRepository.deleteById(scopeId);
        if (scope.getPublishedFeedId() != null) {
            icsExportService.invalidate(scope.getPublishedFeedId());
        }
    }

    private void rejectIfNonWritableCalendarScope(RuleScope scope) {
        if (scope.getCalendarId() == null) {
            return;
        }
        calendarRepository.findById(scope.getCalendarId()).ifPresent(calendar -> {
            if (!calendar.isWritable()) {
                throw new IllegalArgumentException(
                        "Cannot switch this rule to DELETE while it is scoped to a read-only calendar (\""
                                + calendar.getName() + "\")");
            }
        });
    }
}
