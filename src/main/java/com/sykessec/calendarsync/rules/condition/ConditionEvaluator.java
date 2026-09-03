package com.sykessec.calendarsync.rules.condition;

import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.provider.ProviderEvent;

import java.util.Set;

/** One evaluator per RuleField, each declaring which operators actually make sense for it. */
public interface ConditionEvaluator {

    Set<RuleOperator> supportedOperators();

    boolean evaluate(ProviderEvent event, RuleCondition condition);

    default void checkOperatorSupported(RuleOperator operator) {
        if (!supportedOperators().contains(operator)) {
            throw new IllegalArgumentException(
                    "Operator " + operator + " is not valid for this field");
        }
    }

    /**
     * Checks that a condition's value is something this evaluator can
     * actually parse, at rule-save time. Without it the parse only happens
     * during evaluation - inside the sync job or a feed regeneration - where
     * the resulting unchecked exception is far from the user who typed the
     * value and aborts work that has nothing to do with that rule. The
     * message is shown to that user, so it says what to type.
     */
    default void checkValueUsable(RuleOperator operator, String value) {
    }
}
