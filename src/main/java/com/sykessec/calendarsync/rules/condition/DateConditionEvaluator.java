package com.sykessec.calendarsync.rules.condition;

import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.provider.ProviderEvent;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Handles START - BEFORE/AFTER/EQUALS against an ISO-8601 instant condition value. */
public class DateConditionEvaluator implements ConditionEvaluator {

    private static final Set<RuleOperator> SUPPORTED =
            EnumSet.of(RuleOperator.EQUALS, RuleOperator.BEFORE, RuleOperator.AFTER);

    @Override
    public Set<RuleOperator> supportedOperators() {
        return SUPPORTED;
    }

    @Override
    public void checkValueUsable(RuleOperator operator, String value) {
        try {
            Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("START needs an ISO-8601 instant in UTC, "
                    + "e.g. 2026-01-31T09:00:00Z - got \"" + value + "\"");
        }
    }

    @Override
    public boolean evaluate(ProviderEvent event, RuleCondition condition) {
        checkOperatorSupported(condition.getOperator());

        if (event.start() == null) {
            return false;
        }
        Instant threshold = Instant.parse(condition.getValue());

        return switch (condition.getOperator()) {
            case BEFORE -> event.start().isBefore(threshold);
            case AFTER -> event.start().isAfter(threshold);
            case EQUALS -> event.start().equals(threshold);
            default -> throw new IllegalArgumentException("Unsupported operator: " + condition.getOperator());
        };
    }
}
