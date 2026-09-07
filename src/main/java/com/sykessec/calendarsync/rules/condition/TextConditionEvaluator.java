package com.sykessec.calendarsync.rules.condition;

import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.enums.RuleField;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.provider.ProviderEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Handles TITLE, DESCRIPTION, LOCATION, CALENDAR_NAME, ATTENDEE - the
 * string operators only.
 *
 * EnumSet rather than Set.of: the operator picker lists these in iteration
 * order, and an EnumSet iterates in RuleOperator's declaration order, which
 * puts each negated operator directly beneath the one it negates. Set.of's
 * order is unspecified and varies between JVM runs.
 */
public class TextConditionEvaluator implements ConditionEvaluator {

    private static final Set<RuleOperator> SUPPORTED = EnumSet.of(
            RuleOperator.CONTAINS, RuleOperator.NOT_CONTAINS,
            RuleOperator.STARTS_WITH, RuleOperator.NOT_STARTS_WITH,
            RuleOperator.EQUALS, RuleOperator.REGEX);

    @Override
    public Set<RuleOperator> supportedOperators() {
        return SUPPORTED;
    }

    @Override
    public void checkValueUsable(RuleOperator operator, String value) {
        if (operator != RuleOperator.REGEX) {
            return;
        }
        try {
            Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("That isn't a valid regular expression: " + e.getDescription()
                    + (e.getIndex() >= 0 ? " (at position " + e.getIndex() + ")" : ""));
        }
    }

    /**
     * A negated operator inverts the result of the WHOLE match, not the
     * per-candidate test.
     *
     * That distinction only shows up on ATTENDEE, which is the one multi-valued
     * field here, and it is the difference between two very different rules.
     * "Attendee contains bob@" is true when SOME attendee matches, so its
     * negation has to mean NO attendee matches. Inverting inside the lambda
     * would instead ask whether SOME attendee fails to match, which is true of
     * almost every event with more than one attendee - including every meeting
     * Bob is actually in.
     *
     * The consequence for single-valued fields is that a missing one matches a
     * negated operator: candidateValues turns a null title into "", which
     * contains nothing, so "does not contain" holds. That is the correct
     * reading of the words, and the condition editor warns about it.
     */
    @Override
    public boolean evaluate(ProviderEvent event, RuleCondition condition) {
        RuleOperator operator = condition.getOperator();
        checkOperatorSupported(operator);

        List<String> candidates = candidateValues(event, condition.getField());
        String value = condition.getValue();
        boolean caseSensitive = condition.isCaseSensitive();
        RuleOperator comparison = operator.positiveForm();
        // Compiled once per condition rather than once per candidate string:
        // evaluate() runs for every event of every synced calendar.
        Pattern pattern = comparison == RuleOperator.REGEX
                ? Pattern.compile(value, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE)
                : null;

        boolean matched = candidates.stream()
                .anyMatch(candidate -> matches(candidate, comparison, value, caseSensitive, pattern));
        return operator.isNegated() != matched;
    }

    private boolean matches(String candidate, RuleOperator comparison, String value,
                             boolean caseSensitive, Pattern pattern) {
        if (candidate == null) {
            return false;
        }
        return switch (comparison) {
            // Locale.ROOT, not the JVM default. A default-locale toLowerCase()
            // made this the one operator here whose behaviour depended on where
            // the server happened to be configured: under a Turkish locale
            // "MEETING".toLowerCase() is "meetIng" with a dotless i, so a
            // CONTAINS rule for "meeting" silently stopped matching while the
            // same rule written as STARTS_WITH or EQUALS - which use
            // regionMatches and equalsIgnoreCase, both locale-independent -
            // kept working.
            case CONTAINS -> caseSensitive
                    ? candidate.contains(value)
                    : candidate.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
            // regionMatches with ignoreCase does the same job as startsWith
            // without allocating a lowercased copy of every candidate.
            case STARTS_WITH -> candidate.regionMatches(!caseSensitive, 0, value, 0, value.length());
            case EQUALS -> caseSensitive ? candidate.equals(value) : candidate.equalsIgnoreCase(value);
            case REGEX -> pattern.matcher(candidate).find();
            default -> throw new IllegalArgumentException("Unsupported operator: " + comparison);
        };
    }

    private List<String> candidateValues(ProviderEvent event, RuleField field) {
        return switch (field) {
            case TITLE -> List.of(nullToEmpty(event.title()));
            case DESCRIPTION -> List.of(nullToEmpty(event.description()));
            case LOCATION -> List.of(nullToEmpty(event.location()));
            case CALENDAR_NAME -> List.of(nullToEmpty(event.calendarName()));
            case ATTENDEE -> event.attendees() == null ? List.of() : event.attendees();
            default -> throw new IllegalArgumentException("TextConditionEvaluator does not handle field " + field);
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
