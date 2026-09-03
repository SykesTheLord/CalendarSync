package com.sykessec.calendarsync.rules;

import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.provider.ProviderEvent;

import java.util.List;
import java.util.Optional;

/**
 * One evaluation code path shared by every provider AND the ICS export
 * service (Stage 2) - never duplicated per provider.
 */
public interface RuleEngine {

    /** Whether a single condition matches, dispatching to the evaluator registered for its field. */
    boolean evaluateCondition(ProviderEvent event, RuleCondition condition);

    /** Whether a rule matches, applying its match_logic (ANY/ALL) across its conditions. */
    boolean evaluateRule(ProviderEvent event, DeletionRule rule, List<RuleCondition> conditions);

    /**
     * The first enabled rule (in priority order) that matches this event, if any.
     * rulesInPriorityOrder must already be filtered to enabled rules scoped to this event's source.
     */
    Optional<DeletionRule> firstMatch(ProviderEvent event, List<DeletionRule> rulesInPriorityOrder,
                                       java.util.function.Function<Long, List<RuleCondition>> conditionsByRuleId);

    /** Throws IllegalArgumentException if the operator isn't valid for the field - used at rule-save time. */
    void validateFieldOperator(com.sykessec.calendarsync.entity.enums.RuleField field, RuleOperator operator);

    /**
     * The operators this field's evaluator accepts. The same knowledge
     * validateFieldOperator rejects with, offered forwards so the condition
     * editor can list only the operators that will be accepted instead of
     * offering all of them and refusing most combinations on submit.
     */
    java.util.Set<RuleOperator> supportedOperators(com.sykessec.calendarsync.entity.enums.RuleField field);

    /**
     * Everything validateFieldOperator checks, plus that the value itself is
     * parseable by the field's evaluator. Both are rule-save-time checks; the
     * value half exists so a typo surfaces in the form the user is looking at
     * rather than as an unchecked exception inside a background sync.
     */
    void validateCondition(com.sykessec.calendarsync.entity.enums.RuleField field, RuleOperator operator,
                            String value);
}
