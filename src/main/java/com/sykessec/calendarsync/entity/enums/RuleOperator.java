package com.sykessec.calendarsync.entity.enums;

/**
 * How a condition compares its value against an event's field.
 *
 * Declaration order is the order the operator picker offers them, because the
 * evaluators hold their supported sets in an EnumSet and an EnumSet iterates in
 * declaration order. Each negated operator therefore sits directly beneath the
 * one it negates. Order is otherwise free to change: every persisted operator
 * is mapped with EnumType.STRING, so no ordinal is ever written to the database.
 *
 * The negated operators need care rather than a "!" in the evaluator, for two
 * reasons that are easy to get wrong and hard to notice afterwards:
 *
 * 1. ATTENDEE is a list. "Attendee contains bob@" means *some* attendee
 *    contains it, so its negation must mean *no* attendee does - negating the
 *    whole match, not each candidate. Negating per candidate would make
 *    "attendee does not contain bob@" true for any event that has a second
 *    attendee who isn't Bob, which is the opposite of what it says.
 * 2. An absent field satisfies a negated operator. An event with no
 *    description genuinely does not contain anything, so NOT_CONTAINS matches
 *    it. That is the correct reading, and it is also the one that will surprise
 *    someone who writes "title does not contain [Work]" as a DELETE rule and
 *    finds it matched every untitled event too - which is why the condition
 *    editor says so at the point of choosing one.
 */
public enum RuleOperator {
    CONTAINS,
    NOT_CONTAINS,
    STARTS_WITH,
    NOT_STARTS_WITH,
    EQUALS,
    REGEX,
    BEFORE,
    AFTER,
    GT,
    LT;

    /** True for the operators whose result is the inverse of another operator's. */
    public boolean isNegated() {
        return this == NOT_CONTAINS || this == NOT_STARTS_WITH;
    }

    /**
     * The operator actually evaluated against each candidate value; a negated
     * operator inverts the result of its positive form rather than being
     * matched directly. Returns itself for everything else.
     */
    public RuleOperator positiveForm() {
        return switch (this) {
            case NOT_CONTAINS -> CONTAINS;
            case NOT_STARTS_WITH -> STARTS_WITH;
            default -> this;
        };
    }
}
