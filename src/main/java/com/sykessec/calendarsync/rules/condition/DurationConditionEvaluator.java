package com.sykessec.calendarsync.rules.condition;

import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.provider.ProviderEvent;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/** Handles DURATION - GT/LT/EQUALS, condition value is whole minutes. */
public class DurationConditionEvaluator implements ConditionEvaluator {

    private static final Set<RuleOperator> SUPPORTED =
            EnumSet.of(RuleOperator.EQUALS, RuleOperator.GT, RuleOperator.LT);

    @Override
    public Set<RuleOperator> supportedOperators() {
        return SUPPORTED;
    }

    @Override
    public void checkValueUsable(RuleOperator operator, String value) {
        try {
            Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("DURATION needs a whole number of minutes, "
                    + "e.g. 30 - got \"" + value + "\"");
        }
    }

    @Override
    public boolean evaluate(ProviderEvent event, RuleCondition condition) {
        checkOperatorSupported(condition.getOperator());

        if (event.start() == null || event.end() == null) {
            return false;
        }
        long actualMinutes = Duration.between(event.start(), event.end()).toMinutes();
        long thresholdMinutes = Long.parseLong(condition.getValue().trim());

        return switch (condition.getOperator()) {
            case GT -> actualMinutes > thresholdMinutes;
            case LT -> actualMinutes < thresholdMinutes;
            case EQUALS -> actualMinutes == thresholdMinutes;
            default -> throw new IllegalArgumentException("Unsupported operator: " + condition.getOperator());
        };
    }
}
