-- Per-feed export settings: how each retained VEVENT is written out, rather
-- than which events are written. Added as columns on published_feed instead of
-- a side table because there is exactly one profile per feed and no history to
-- keep - a join would buy nothing and cost every regeneration a second query.
--
-- Every default reproduces the behaviour that existed before this migration:
-- IcsCalendarMapper built each VEVENT from normalized fields only, so no feed
-- has ever emitted CLASS, TRANSP, X-MICROSOFT-CDO-BUSYSTATUS or VALARM. An
-- upgraded installation therefore keeps serving byte-identical calendars until
-- someone opens a feed and changes the settings, which is what a subscriber
-- with an already-working calendar app deserves. UNIVERSAL is the default
-- target because it is the only value that can express Out of Office at all,
-- and the Microsoft extension it adds is inert in clients that ignore it.
--
-- SQLite allows ADD COLUMN with a CHECK constraint and a NOT NULL column as
-- long as a constant default is supplied, so this needs no table rebuild
-- (unlike V2). Verified against the sqlite3 CLI before being written.

ALTER TABLE published_feed ADD COLUMN export_target TEXT NOT NULL DEFAULT 'UNIVERSAL'
    CHECK (export_target IN ('UNIVERSAL','GOOGLE','OUTLOOK','PROTON'));

ALTER TABLE published_feed ADD COLUMN event_classification TEXT NOT NULL DEFAULT 'UNCHANGED'
    CHECK (event_classification IN ('UNCHANGED','PUBLIC','PRIVATE','CONFIDENTIAL'));

ALTER TABLE published_feed ADD COLUMN event_busy_status TEXT NOT NULL DEFAULT 'UNCHANGED'
    CHECK (event_busy_status IN ('UNCHANGED','FREE','BUSY','TENTATIVE','OUT_OF_OFFICE'));

ALTER TABLE published_feed ADD COLUMN alarm_policy TEXT NOT NULL DEFAULT 'STRIP'
    CHECK (alarm_policy IN ('STRIP','PASSTHROUGH','FIXED'));

-- Only read when alarm_policy = 'FIXED'. Bounded to four weeks; the upper bound
-- is repeated in ExportProfile so a bad value is rejected with a message the
-- user can read rather than as a constraint violation from deep in Hibernate.
ALTER TABLE published_feed ADD COLUMN alarm_minutes_before INTEGER NOT NULL DEFAULT 15
    CHECK (alarm_minutes_before BETWEEN 0 AND 40320);
