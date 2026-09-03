package com.sykessec.calendarsync.rules;

import com.sykessec.calendarsync.entity.DeletionRule;
import com.sykessec.calendarsync.entity.RuleCondition;
import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.entity.enums.RuleField;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.provider.ProviderEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEngineImplTest {

    private final RuleEngine engine = new RuleEngineImpl();

    private ProviderEvent event(String title, String description, String location,
                                 List<String> attendees, Instant start, Instant end,
                                 boolean recurring, String calendarName) {
        return new ProviderEvent("uid-1", title, description, location, attendees, start, end,
                recurring, calendarName, null, null);
    }

    private RuleCondition condition(RuleField field, RuleOperator operator, String value, boolean caseSensitive) {
        RuleCondition c = new RuleCondition();
        c.setField(field);
        c.setOperator(operator);
        c.setValue(value);
        c.setCaseSensitive(caseSensitive);
        return c;
    }

    // --- TEXT fields: CONTAINS / EQUALS / REGEX, case sensitivity ---

    @Test
    void containsMatchesCaseInsensitiveByDefault() {
        ProviderEvent e = event("Weekly Standup", null, null, List.of(), null, null, false, null);
        RuleCondition c = condition(RuleField.TITLE, RuleOperator.CONTAINS, "standup", false);
        assertThat(engine.evaluateCondition(e, c)).isTrue();
    }

    @Test
    void containsRespectsCaseSensitiveFlag() {
        ProviderEvent e = event("Weekly Standup", null, null, List.of(), null, null, false, null);
        RuleCondition c = condition(RuleField.TITLE, RuleOperator.CONTAINS, "standup", true);
        assertThat(engine.evaluateCondition(e, c)).isFalse();
    }

    @Test
    void equalsMatchesWholeStringOnly() {
        ProviderEvent e = event("Standup", null, null, List.of(), null, null, false, null);
        assertThat(engine.evaluateCondition(e, condition(RuleField.TITLE, RuleOperator.EQUALS, "Standup", false)))
                .isTrue();
        assertThat(engine.evaluateCondition(e, condition(RuleField.TITLE, RuleOperator.EQUALS, "Stand", false)))
                .isFalse();
    }

    @Test
    void regexMatchesAgainstTitle() {
        ProviderEvent e = event("Sprint Planning #42", null, null, List.of(), null, null, false, null);
        RuleCondition c = condition(RuleField.TITLE, RuleOperator.REGEX, "#\\d+", false);
        assertThat(engine.evaluateCondition(e, c)).isTrue();
    }

    @Test
    void attendeeContainsMatchesAnyAttendee() {
        ProviderEvent e = event("1:1", null, null, List.of("alice@example.com", "bob@example.com"),
                null, null, false, null);
        RuleCondition c = condition(RuleField.ATTENDEE, RuleOperator.CONTAINS, "bob@", false);
        assertThat(engine.evaluateCondition(e, c)).isTrue();
    }

    @Test
    void calendarNameField() {
        ProviderEvent e = event("x", null, null, List.of(), null, null, false, "Work Holidays");
        RuleCondition c = condition(RuleField.CALENDAR_NAME, RuleOperator.EQUALS, "Work Holidays", false);
        assertThat(engine.evaluateCondition(e, c)).isTrue();
    }

    // --- DURATION: GT / LT / EQUALS, value in minutes ---

    @Test
    void durationGreaterThanThreshold() {
        Instant start = Instant.parse("2026-01-01T10:00:00Z");
        ProviderEvent e = event("x", null, null, List.of(), start, start.plus(90, ChronoUnit.MINUTES), false, null);
        assertThat(engine.evaluateCondition(e, condition(RuleField.DURATION, RuleOperator.GT, "60", false)))
                .isTrue();
        assertThat(engine.evaluateCondition(e, condition(RuleField.DURATION, RuleOperator.LT, "60", false)))
                .isFalse();
    }

    @Test
    void durationEquals() {
        Instant start = Instant.parse("2026-01-01T10:00:00Z");
        ProviderEvent e = event("x", null, null, List.of(), start, start.plus(30, ChronoUnit.MINUTES), false, null);
        assertThat(engine.evaluateCondition(e, condition(RuleField.DURATION, RuleOperator.EQUALS, "30", false)))
                .isTrue();
    }

    // --- START: BEFORE / AFTER / EQUALS ---

    @Test
    void startBeforeAndAfter() {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        ProviderEvent e = event("x", null, null, List.of(), start, null, false, null);
        assertThat(engine.evaluateCondition(e,
                condition(RuleField.START, RuleOperator.BEFORE, "2027-01-01T00:00:00Z", false))).isTrue();
        assertThat(engine.evaluateCondition(e,
                condition(RuleField.START, RuleOperator.AFTER, "2027-01-01T00:00:00Z", false))).isFalse();
    }

    // --- RECURRENCE: EQUALS only ---

    @Test
    void recurrenceMatchesBooleanValue() {
        ProviderEvent recurring = event("x", null, null, List.of(), null, null, true, null);
        ProviderEvent single = event("x", null, null, List.of(), null, null, false, null);
        RuleCondition c = condition(RuleField.RECURRENCE, RuleOperator.EQUALS, "true", false);
        assertThat(engine.evaluateCondition(recurring, c)).isTrue();
        assertThat(engine.evaluateCondition(single, c)).isFalse();
    }

    // --- Invalid field/operator combinations are rejected ---

    @Test
    void regexIsRejectedForDuration() {
        assertThatThrownBy(() -> engine.validateFieldOperator(RuleField.DURATION, RuleOperator.REGEX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void greaterThanIsRejectedForTitle() {
        assertThatThrownBy(() -> engine.validateFieldOperator(RuleField.TITLE, RuleOperator.GT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validCombinationsDoNotThrow() {
        engine.validateFieldOperator(RuleField.TITLE, RuleOperator.CONTAINS);
        engine.validateFieldOperator(RuleField.DURATION, RuleOperator.GT);
        engine.validateFieldOperator(RuleField.START, RuleOperator.BEFORE);
        engine.validateFieldOperator(RuleField.RECURRENCE, RuleOperator.EQUALS);
    }

    // --- Condition VALUES are rejected at save time, not mid-sync ---

    @Test
    void unparseableStartInstantIsRejected() {
        assertThatThrownBy(() -> engine.validateCondition(RuleField.START, RuleOperator.BEFORE, "next tuesday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void nonNumericDurationIsRejected() {
        assertThatThrownBy(() -> engine.validateCondition(RuleField.DURATION, RuleOperator.GT, "half an hour"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minutes");
    }

    @Test
    void invalidRegexIsRejected() {
        assertThatThrownBy(() -> engine.validateCondition(RuleField.TITLE, RuleOperator.REGEX, "Stand(up"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular expression");
    }

    @Test
    void blankValueIsRejected() {
        assertThatThrownBy(() -> engine.validateCondition(RuleField.TITLE, RuleOperator.CONTAINS, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usableConditionValuesAreAccepted() {
        engine.validateCondition(RuleField.START, RuleOperator.BEFORE, "2026-01-31T09:00:00Z");
        engine.validateCondition(RuleField.DURATION, RuleOperator.GT, "30");
        engine.validateCondition(RuleField.TITLE, RuleOperator.REGEX, "^Stand.?up$");
        // A non-REGEX text value is free-form and must not be regex-validated.
        engine.validateCondition(RuleField.TITLE, RuleOperator.CONTAINS, "Stand(up");
    }

    // --- ANY vs ALL match logic ---

    @Test
    void anyLogicMatchesIfOneConditionMatches() {
        ProviderEvent e = event("Team Sync", "boring", null, List.of(), null, null, false, null);
        DeletionRule rule = new DeletionRule();
        rule.setMatchLogic(MatchLogic.ANY);
        List<RuleCondition> conditions = List.of(
                condition(RuleField.TITLE, RuleOperator.CONTAINS, "nonexistent", false),
                condition(RuleField.DESCRIPTION, RuleOperator.CONTAINS, "boring", false));
        assertThat(engine.evaluateRule(e, rule, conditions)).isTrue();
    }

    @Test
    void allLogicRequiresEveryConditionToMatch() {
        ProviderEvent e = event("Team Sync", "boring", null, List.of(), null, null, false, null);
        DeletionRule rule = new DeletionRule();
        rule.setMatchLogic(MatchLogic.ALL);
        List<RuleCondition> conditions = List.of(
                condition(RuleField.TITLE, RuleOperator.CONTAINS, "nonexistent", false),
                condition(RuleField.DESCRIPTION, RuleOperator.CONTAINS, "boring", false));
        assertThat(engine.evaluateRule(e, rule, conditions)).isFalse();

        List<RuleCondition> bothMatch = List.of(
                condition(RuleField.TITLE, RuleOperator.CONTAINS, "Team", false),
                condition(RuleField.DESCRIPTION, RuleOperator.CONTAINS, "boring", false));
        assertThat(engine.evaluateRule(e, rule, bothMatch)).isTrue();
    }

    @Test
    void ruleWithNoConditionsNeverMatches() {
        ProviderEvent e = event("Anything", null, null, List.of(), null, null, false, null);
        DeletionRule rule = new DeletionRule();
        rule.setMatchLogic(MatchLogic.ANY);
        assertThat(engine.evaluateRule(e, rule, List.of())).isFalse();
    }

    // --- STARTS_WITH and the negated operators ---

    @Test
    void startsWithMatchesOnlyAtTheBeginning() {
        ProviderEvent e = event("Weekly Standup", null, null, List.of(), null, null, false, null);
        assertThat(engine.evaluateCondition(e, condition(RuleField.TITLE, RuleOperator.STARTS_WITH, "weekly", false)))
                .isTrue();
        assertThat(engine.evaluateCondition(e, condition(RuleField.TITLE, RuleOperator.STARTS_WITH, "Standup", false)))
                .isFalse();
    }

    @Test
    void startsWithHonoursCaseSensitivity() {
        ProviderEvent e = event("Weekly Standup", null, null, List.of(), null, null, false, null);
        assertThat(engine.evaluateCondition(e, condition(RuleField.TITLE, RuleOperator.STARTS_WITH, "weekly", true)))
                .isFalse();
        assertThat(engine.evaluateCondition(e, condition(RuleField.TITLE, RuleOperator.STARTS_WITH, "Weekly", true)))
                .isTrue();
    }

    @Test
    void notContainsIsTheExactInverseOfContains() {
        ProviderEvent e = event("Weekly Standup", null, null, List.of(), null, null, false, null);
        assertThat(engine.evaluateCondition(e, condition(RuleField.TITLE, RuleOperator.NOT_CONTAINS, "standup", false)))
                .isFalse();
        assertThat(engine.evaluateCondition(e, condition(RuleField.TITLE, RuleOperator.NOT_CONTAINS, "retro", false)))
                .isTrue();
    }

    @Test
    void notStartsWithIsTheExactInverseOfStartsWith() {
        ProviderEvent e = event("Weekly Standup", null, null, List.of(), null, null, false, null);
        assertThat(engine.evaluateCondition(e,
                condition(RuleField.TITLE, RuleOperator.NOT_STARTS_WITH, "Weekly", false))).isFalse();
        assertThat(engine.evaluateCondition(e,
                condition(RuleField.TITLE, RuleOperator.NOT_STARTS_WITH, "Standup", false))).isTrue();
    }

    @Test
    void aNegatedOperatorMatchesAnEventWhoseFieldIsMissing() {
        // The correct reading - an event with no description does not contain
        // anything - and the one that surprises people, which is why the
        // condition editor warns about it. Pinned so it can't drift silently.
        ProviderEvent e = event("Weekly Standup", null, null, List.of(), null, null, false, null);
        assertThat(engine.evaluateCondition(e,
                condition(RuleField.DESCRIPTION, RuleOperator.NOT_CONTAINS, "boring", false))).isTrue();
        assertThat(engine.evaluateCondition(e,
                condition(RuleField.LOCATION, RuleOperator.NOT_STARTS_WITH, "Room", false))).isTrue();
    }

    @Test
    void notContainsOnAttendeesMeansNoAttendeeMatches() {
        // The bug this guards: negating per candidate instead of negating the
        // whole match. Bob IS on this event, so "attendee does not contain
        // bob@" must be false - even though alice@ does not contain it.
        ProviderEvent withBob = event("Sync", null, null, List.of("alice@example.com", "bob@example.com"),
                null, null, false, null);
        assertThat(engine.evaluateCondition(withBob,
                condition(RuleField.ATTENDEE, RuleOperator.NOT_CONTAINS, "bob@", false))).isFalse();

        ProviderEvent withoutBob = event("Sync", null, null, List.of("alice@example.com", "carol@example.com"),
                null, null, false, null);
        assertThat(engine.evaluateCondition(withoutBob,
                condition(RuleField.ATTENDEE, RuleOperator.NOT_CONTAINS, "bob@", false))).isTrue();
    }

    @Test
    void notContainsOnAnEventWithNoAttendeesMatches() {
        ProviderEvent e = event("Solo work", null, null, List.of(), null, null, false, null);
        assertThat(engine.evaluateCondition(e,
                condition(RuleField.ATTENDEE, RuleOperator.NOT_CONTAINS, "bob@", false))).isTrue();
    }

    @Test
    void negatedOperatorsAreOfferedForTextFieldsOnly() {
        assertThat(engine.supportedOperators(RuleField.TITLE))
                .contains(RuleOperator.NOT_CONTAINS, RuleOperator.STARTS_WITH, RuleOperator.NOT_STARTS_WITH);
        assertThat(engine.supportedOperators(RuleField.DURATION))
                .doesNotContain(RuleOperator.NOT_CONTAINS, RuleOperator.NOT_STARTS_WITH);
        assertThatThrownBy(() -> engine.validateFieldOperator(RuleField.START, RuleOperator.NOT_CONTAINS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOperatorPickerListsEachNegationBesideItsPositiveForm() {
        // EnumSet iteration order is RuleOperator's declaration order, and the
        // picker shows them in iteration order. Set.of would vary per JVM run.
        assertThat(engine.supportedOperators(RuleField.TITLE))
                .containsExactly(RuleOperator.CONTAINS, RuleOperator.NOT_CONTAINS,
                        RuleOperator.STARTS_WITH, RuleOperator.NOT_STARTS_WITH,
                        RuleOperator.EQUALS, RuleOperator.REGEX);
    }
}
