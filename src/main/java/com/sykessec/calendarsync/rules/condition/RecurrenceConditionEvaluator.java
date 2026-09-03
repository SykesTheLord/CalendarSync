package com.sykessec.calendarsync.rules.condition;

import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.provider.ProviderEvent;

import java.util.EnumSet;
import java.util.Set;

/** Handles RECURRENCE - EQUALS only, condition value is "true"/"false". */
public class RecurrenceConditionEvaluator implements ConditionEvaluator {

    private static final Set<RuleOperator> SUPPORTED = EnumSet.of(RuleOperator.EQUALS);

    @Override
    public Set<RuleOperator> supportedOperators() {
        return SUPPORTED;
    }

    @Override
    public boolean evaluate(ProviderEvent event, RuleCondition condition) {
        checkOperatorSupported(condition.getOperator());
        boolean expected = Boolean.parseBoolean(condition.getValue());
        return event.recurring() == expected;
    }
}
