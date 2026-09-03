-- rule_scope was created in V1 without a surrogate key, but the RuleScope
-- entity has always mapped an @Id Long id - and RuleScopeRepository.deleteById
-- (via DeletionRuleService.removeScope) needs one. The result was that EVERY
-- statement Hibernate generated against the table selected a column that did
-- not exist:
--
--   select rs1_0.id, ... from rule_scope rs1_0 where rs1_0.rule_id=?
--   -> [SQLITE_ERROR] no such column: rs1_0.id
--
-- so the whole rule-scoping feature (opening a rule's edit dialog, scoping a
-- rule to a calendar or feed, and the published-feed filtering path that
-- resolves a feed's rules through rule_scope) failed at runtime.
--
-- SQLite cannot ALTER TABLE ... ADD COLUMN a PRIMARY KEY, so the table has to
-- be rebuilt and its rows copied. Nothing else in the schema has a foreign key
-- pointing AT rule_scope, so the drop/rename is safe to do with foreign key
-- enforcement on (see DataSourceConfig, which switches it on per connection).

CREATE TABLE rule_scope_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_id INTEGER NOT NULL REFERENCES deletion_rule(id) ON DELETE CASCADE,
    calendar_id INTEGER REFERENCES calendar(id) ON DELETE CASCADE,
    published_feed_id INTEGER REFERENCES published_feed(id) ON DELETE CASCADE
);

INSERT INTO rule_scope_new (rule_id, calendar_id, published_feed_id)
    SELECT rule_id, calendar_id, published_feed_id FROM rule_scope;

DROP INDEX IF EXISTS idx_rule_scope_rule_id;
DROP TABLE rule_scope;
ALTER TABLE rule_scope_new RENAME TO rule_scope;

CREATE INDEX idx_rule_scope_rule_id ON rule_scope(rule_id);
