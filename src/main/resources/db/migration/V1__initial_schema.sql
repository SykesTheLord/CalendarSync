-- Full schema for CalendarSync, committed whole in Stage 0 even though this
-- stage's application code only exercises app_user.
--
-- SQLite caveat: SQLite's ALTER TABLE support is limited (no ALTER/DROP
-- COLUMN, no adding constraints to existing tables) and its locking model
-- does not support Flyway's usual concurrent-migration advisory lock -
-- never run migrations from more than one app instance concurrently, and
-- never edit this file after it has run anywhere. Any future schema change
-- must be a new V2__....sql file.

CREATE TABLE app_user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN')),
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE calendar_connection (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider TEXT NOT NULL CHECK (provider IN ('GOOGLE','MS_GRAPH','ICLOUD','CALDAV','ICS_SOURCE')),
    display_name TEXT NOT NULL,
    auth_type TEXT NOT NULL,
    encrypted_credentials BLOB,
    caldav_base_url TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_calendar_connection_user_id ON calendar_connection(user_id);

CREATE TABLE calendar (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    connection_id INTEGER NOT NULL REFERENCES calendar_connection(id) ON DELETE CASCADE,
    remote_calendar_id TEXT,
    name TEXT NOT NULL,
    is_writable INTEGER NOT NULL DEFAULT 1,
    color TEXT,
    sync_token TEXT,
    ctag TEXT
);

CREATE INDEX idx_calendar_connection_id ON calendar(connection_id);

CREATE TABLE published_feed (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    access_token TEXT NOT NULL UNIQUE,
    last_generated_at TEXT,
    cache_ttl_seconds INTEGER NOT NULL DEFAULT 3600
);

CREATE INDEX idx_published_feed_user_id ON published_feed(user_id);

CREATE TABLE published_feed_source (
    published_feed_id INTEGER NOT NULL REFERENCES published_feed(id) ON DELETE CASCADE,
    calendar_id INTEGER NOT NULL REFERENCES calendar(id) ON DELETE CASCADE,
    PRIMARY KEY (published_feed_id, calendar_id)
);

-- Lets a restored feed-side "deletion" force an event back into the output
-- even though the rule that excluded it is still enabled and would otherwise match again.
CREATE TABLE published_feed_override (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    published_feed_id INTEGER NOT NULL REFERENCES published_feed(id) ON DELETE CASCADE,
    event_uid TEXT NOT NULL,
    override TEXT NOT NULL CHECK (override IN ('FORCE_INCLUDE','FORCE_EXCLUDE')),
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (published_feed_id, event_uid)
);

CREATE TABLE sync_state (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    calendar_id INTEGER NOT NULL REFERENCES calendar(id) ON DELETE CASCADE,
    last_sync_token TEXT,
    last_ctag TEXT,
    last_synced_at TEXT,
    last_error TEXT
);

CREATE INDEX idx_sync_state_calendar_id ON sync_state(calendar_id);

CREATE TABLE deletion_rule (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    match_logic TEXT NOT NULL CHECK (match_logic IN ('ANY','ALL')),
    action TEXT NOT NULL CHECK (action IN ('DELETE','DRY_RUN','TAG')),
    priority INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_deletion_rule_user_id ON deletion_rule(user_id);

CREATE TABLE rule_scope (
    rule_id INTEGER NOT NULL REFERENCES deletion_rule(id) ON DELETE CASCADE,
    calendar_id INTEGER REFERENCES calendar(id) ON DELETE CASCADE,
    published_feed_id INTEGER REFERENCES published_feed(id) ON DELETE CASCADE
);

CREATE INDEX idx_rule_scope_rule_id ON rule_scope(rule_id);

CREATE TABLE rule_condition (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_id INTEGER NOT NULL REFERENCES deletion_rule(id) ON DELETE CASCADE,
    field TEXT NOT NULL CHECK (field IN ('TITLE','DESCRIPTION','LOCATION','CALENDAR_NAME','ATTENDEE','DURATION','START','RECURRENCE')),
    operator TEXT NOT NULL CHECK (operator IN ('CONTAINS','EQUALS','REGEX','BEFORE','AFTER','GT','LT')),
    value TEXT NOT NULL,
    case_sensitive INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_rule_condition_rule_id ON rule_condition(rule_id);

-- One row per deletion (real or feed-side). event_snapshot must contain enough
-- to fully recreate the event; this table IS the trash/restore mechanism, not just a log.
CREATE TABLE deletion_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    rule_id INTEGER REFERENCES deletion_rule(id) ON DELETE SET NULL,
    connection_id INTEGER REFERENCES calendar_connection(id) ON DELETE SET NULL,
    calendar_id INTEGER REFERENCES calendar(id) ON DELETE SET NULL,
    published_feed_id INTEGER REFERENCES published_feed(id) ON DELETE SET NULL,
    event_uid TEXT,
    event_summary TEXT,
    event_snapshot TEXT,
    snapshot_format TEXT CHECK (snapshot_format IN ('ICS','GOOGLE_JSON','MS_GRAPH_JSON')),
    action_taken TEXT NOT NULL CHECK (action_taken IN ('DELETE','DRY_RUN','TAG')),
    status TEXT NOT NULL DEFAULT 'DELETED' CHECK (status IN ('DELETED','RESTORED','PURGED')),
    occurred_at TEXT NOT NULL DEFAULT (datetime('now')),
    restored_at TEXT,
    restored_event_uid TEXT,
    success INTEGER NOT NULL,
    error TEXT
);

CREATE INDEX idx_deletion_audit_user_id ON deletion_audit(user_id);
CREATE INDEX idx_deletion_audit_status ON deletion_audit(status);
