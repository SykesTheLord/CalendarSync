-- Widens rule_condition.operator's CHECK constraint to admit STARTS_WITH and
-- the two negated operators, NOT_CONTAINS and NOT_STARTS_WITH.
--
-- SQLite cannot alter a CHECK constraint, so - as in V2 - the table has to be
-- rebuilt and its rows copied. Two details matter here that did not in V2:
--
--   * id is copied explicitly rather than left to AUTOINCREMENT. Nothing holds
--     a foreign key to rule_condition, so renumbering would not dangle
--     anything, but the ids are what ConditionEditorComponent's Remove button
--     passes to deleteCondition - silently renumbering live rows under an open
--     rule dialog would make it delete the wrong condition.
--   * The FK to deletion_rule points OUT of this table and nothing points at
--     it, so the drop and rename are safe with foreign key enforcement on
--     (DataSourceConfig switches it on per connection).
--
-- The field CHECK and every other column are reproduced exactly as V1 declared
-- them; only the operator list changes.

CREATE TABLE rule_condition_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_id INTEGER NOT NULL REFERENCES deletion_rule(id) ON DELETE CASCADE,
    field TEXT NOT NULL CHECK (field IN ('TITLE','DESCRIPTION','LOCATION','CALENDAR_NAME','ATTENDEE','DURATION','START','RECURRENCE')),
    operator TEXT NOT NULL CHECK (operator IN ('CONTAINS','NOT_CONTAINS','STARTS_WITH','NOT_STARTS_WITH','EQUALS','REGEX','BEFORE','AFTER','GT','LT')),
    value TEXT NOT NULL,
    case_sensitive INTEGER NOT NULL DEFAULT 0
);

INSERT INTO rule_condition_new (id, rule_id, field, operator, value, case_sensitive)
    SELECT id, rule_id, field, operator, value, case_sensitive FROM rule_condition;

DROP INDEX IF EXISTS idx_rule_condition_rule_id;
DROP TABLE rule_condition;
ALTER TABLE rule_condition_new RENAME TO rule_condition;

CREATE INDEX idx_rule_condition_rule_id ON rule_condition(rule_id);
