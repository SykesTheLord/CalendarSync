package com.sykessec.calendarsync.rules;

import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.entity.enums.RuleField;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.provider.ProviderEvent;
import com.sykessec.calendarsync.rules.condition.ConditionEvaluator;
import com.sykessec.calendarsync.rules.condition.DateConditionEvaluator;
import com.sykessec.calendarsync.rules.condition.DurationConditionEvaluator;
import com.sykessec.calendarsync.rules.condition.RecurrenceConditionEvaluator;
import com.sykessec.calendarsync.rules.condition.TextConditionEvaluator;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Service
public class RuleEngineImpl implements RuleEngine {

    private final Map<RuleField, ConditionEvaluator> evaluators;

    public RuleEngineImpl() {
        TextConditionEvaluator text = new TextConditionEvaluator();
        Map<RuleField, ConditionEvaluator> map = new EnumMap<>(RuleField.class);
        map.put(RuleField.TITLE, text);
        map.put(RuleField.DESCRIPTION, text);
        map.put(RuleField.LOCATION, text);
        map.put(RuleField.CALENDAR_NAME, text);
        map.put(RuleField.ATTENDEE, text);
        map.put(RuleField.DURATION, new DurationConditionEvaluator());
        map.put(RuleField.START, new DateConditionEvaluator());
        map.put(RuleField.RECURRENCE, new RecurrenceConditionEvaluator());
        this.evaluators = map;
    }

    @Override
    public boolean evaluateCondition(ProviderEvent event, RuleCondition condition) {
        return evaluatorFor(condition.getField()).evaluate(event, condition);
    }

    @Override
    public boolean evaluateRule(ProviderEvent event, DeletionRule rule, List<RuleCondition> conditions) {
        if (conditions.isEmpty()) {
            return false;
        }
        return rule.getMatchLogic() == MatchLogic.ALL
                ? conditions.stream().allMatch(c -> evaluateCondition(event, c))
                : conditions.stream().anyMatch(c -> evaluateCondition(event, c));
    }

    @Override
    public Optional<DeletionRule> firstMatch(ProviderEvent event, List<DeletionRule> rulesInPriorityOrder,
                                              Function<Long, List<RuleCondition>> conditionsByRuleId) {
        return rulesInPriorityOrder.stream()
                .filter(DeletionRule::isEnabled)
                .filter(rule -> evaluateRule(event, rule, conditionsByRuleId.apply(rule.getId())))
                .findFirst();
    }

    @Override
    public void validateFieldOperator(RuleField field, RuleOperator operator) {
        evaluatorFor(field).checkOperatorSupported(operator);
    }

    @Override
    public java.util.Set<RuleOperator> supportedOperators(RuleField field) {
        return field == null ? java.util.Set.of() : evaluatorFor(field).supportedOperators();
    }

    @Override
    public void validateCondition(RuleField field, RuleOperator operator, String value) {
        ConditionEvaluator evaluator = evaluatorFor(field);
        evaluator.checkOperatorSupported(operator);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A condition needs a value");
        }
        evaluator.checkValueUsable(operator, value);
    }

    private ConditionEvaluator evaluatorFor(RuleField field) {
        ConditionEvaluator evaluator = evaluators.get(field);
        if (evaluator == null) {
            throw new IllegalArgumentException("No evaluator registered for field " + field);
        }
        return evaluator;
    }
}
