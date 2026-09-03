package com.sykessec.calendarsync.ui;

import com.sykessec.calendarsync.entity.enums.AlarmPolicy;
import com.sykessec.calendarsync.entity.enums.AuditStatus;
import com.sykessec.calendarsync.entity.enums.EventBusyStatus;
import com.sykessec.calendarsync.entity.enums.EventClassification;
import com.sykessec.calendarsync.entity.enums.ExportTarget;
import com.sykessec.calendarsync.entity.enums.MatchLogic;
import com.sykessec.calendarsync.entity.enums.ProviderType;
import com.sykessec.calendarsync.entity.enums.Role;
import com.sykessec.calendarsync.entity.enums.RuleAction;
import com.sykessec.calendarsync.entity.enums.RuleField;
import com.sykessec.calendarsync.entity.enums.RuleOperator;
import com.sykessec.calendarsync.ics.ExportProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable names for the enums the UI shows. Constant names like
 * ICS_SOURCE, MS_GRAPH, DRY_RUN and GT are how the database and the code
 * spell them; they are not how a person reads them, and they used to reach
 * the screen verbatim in every grid and picker.
 *
 * Every method takes null, because a cleared ComboBox hands one back.
 */
public final class UiLabels {

    private UiLabels() {
    }

    public static String of(ProviderType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case ICS_SOURCE -> "ICS feed (read-only)";
            case CALDAV -> "CalDAV server";
            case ICLOUD -> "iCloud";
            case GOOGLE -> "Google Calendar";
            case MS_GRAPH -> "Microsoft 365";
        };
    }

    /** How the stored credential is shaped - see ConnectionForm's authTypeFor. */
    public static String authType(String authType) {
        if (authType == null) {
            return "";
        }
        return switch (authType) {
            case "url" -> "Public URL";
            case "basic" -> "Username & password";
            case "app_password" -> "App-specific password";
            case "oauth2" -> "OAuth sign-in";
            default -> authType;
        };
    }

    public static String of(RuleField field) {
        if (field == null) {
            return "";
        }
        return switch (field) {
            case TITLE -> "Title";
            case DESCRIPTION -> "Description";
            case LOCATION -> "Location";
            case CALENDAR_NAME -> "Calendar name";
            case ATTENDEE -> "Attendee";
            case DURATION -> "Duration";
            case START -> "Start time";
            case RECURRENCE -> "Recurring";
        };
    }

    public static String of(RuleOperator operator) {
        if (operator == null) {
            return "";
        }
        return switch (operator) {
            case CONTAINS -> "contains";
            case NOT_CONTAINS -> "does not contain";
            case STARTS_WITH -> "starts with";
            case NOT_STARTS_WITH -> "does not start with";
            case EQUALS -> "is exactly";
            case REGEX -> "matches regex";
            case BEFORE -> "is before";
            case AFTER -> "is after";
            case GT -> "is longer than";
            case LT -> "is shorter than";
        };
    }

    public static String of(RuleAction action) {
        if (action == null) {
            return "";
        }
        return switch (action) {
            case DELETE -> "Delete";
            case DRY_RUN -> "Dry run (log only)";
            case TAG -> "Tag";
        };
    }

    public static String of(MatchLogic logic) {
        if (logic == null) {
            return "";
        }
        return switch (logic) {
            case ANY -> "Match any condition";
            case ALL -> "Match all conditions";
        };
    }

    public static String of(AuditStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case DELETED -> "Deleted";
            case RESTORED -> "Restored";
            case PURGED -> "Purged";
        };
    }

    public static String of(Role role) {
        if (role == null) {
            return "";
        }
        return switch (role) {
            case USER -> "User";
            case ADMIN -> "Administrator";
        };
    }

    public static String of(ExportTarget target) {
        if (target == null) {
            return "";
        }
        return switch (target) {
            case UNIVERSAL -> "Every calendar app";
            case GOOGLE -> "Google Calendar";
            case OUTLOOK -> "Outlook / Microsoft 365";
            case PROTON -> "Proton Calendar";
        };
    }

    /**
     * What the chosen target actually does with these settings. Written for
     * someone deciding what to pick, so it says where a setting will be ignored
     * rather than only what it will do.
     */
    public static String help(ExportTarget target) {
        if (target == null) {
            return "";
        }
        return switch (target) {
            case UNIVERSAL -> "Writes the standard properties plus Microsoft's busy-status extension. "
                    + "Apps that don't understand the extension are required to ignore it, so this is "
                    + "safe everywhere and is the only setting that can say \"Out of Office\".";
            case GOOGLE -> "Standard properties only - Google ignores X-MICROSOFT-* extensions. Google also "
                    + "applies the subscriber's own notification settings to a subscribed calendar, so "
                    + "exported reminders may never be shown.";
            case OUTLOOK -> "Standard properties plus X-MICROSOFT-CDO-BUSYSTATUS, which is what makes Outlook "
                    + "and Exchange show an event as Out of Office rather than merely busy.";
            case PROTON -> "Standard properties only - Proton ignores X-MICROSOFT-* extensions.";
        };
    }

    /**
     * The caveat a negated operator carries, or null when it has none. An
     * absent field satisfies "does not contain" - correct, and the single most
     * surprising thing about these operators - so the editor says it at the
     * moment the operator is chosen rather than leaving it to be discovered by
     * a DELETE rule that matched more than its author expected.
     */
    public static String negationCaveat(RuleOperator operator, RuleField field) {
        if (operator == null || !operator.isNegated()) {
            return null;
        }
        if (field == RuleField.ATTENDEE) {
            return "Matches when NO attendee matches - including events with no attendees at all.";
        }
        return "Also matches events where this field is empty or missing - they contain nothing, "
                + "so they do not contain your value either.";
    }

    public static String of(EventClassification classification) {
        if (classification == null) {
            return "";
        }
        return switch (classification) {
            case UNCHANGED -> "Leave to the calendar app";
            case PUBLIC -> "Public";
            case PRIVATE -> "Private";
            case CONFIDENTIAL -> "Confidential";
        };
    }

    public static String of(EventBusyStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case UNCHANGED -> "Leave to the calendar app";
            case FREE -> "Free";
            case BUSY -> "Busy";
            case TENTATIVE -> "Tentative";
            case OUT_OF_OFFICE -> "Out of office";
        };
    }

    public static String of(AlarmPolicy policy) {
        if (policy == null) {
            return "";
        }
        return switch (policy) {
            case STRIP -> "No reminders";
            case PASSTHROUGH -> "Keep the source's reminders";
            case FIXED -> "One reminder before the event";
        };
    }

    public static String help(AlarmPolicy policy) {
        if (policy == null) {
            return "";
        }
        return switch (policy) {
            case STRIP -> "Every VALARM is left out, so the feed itself never asks for a notification.";
            case PASSTHROUGH -> "Only ICS and CalDAV sources carry reminders to copy - events from Google "
                    + "and Microsoft 365 export without one either way.";
            case FIXED -> "Replaces whatever the source had with a single display reminder.";
        };
    }

    /**
     * A feed's export settings in one line, for the feeds grid - so a feed
     * quietly publishing everything as public and busy is visible without
     * opening it. Untouched feeds say "Default" rather than spelling out three
     * "leave it to the calendar app" values, which would be noise on every row.
     */
    public static String summary(ExportProfile profile) {
        if (profile == null) {
            return "";
        }
        if (profile.classification() == EventClassification.UNCHANGED
                && profile.busyStatus() == EventBusyStatus.UNCHANGED
                && profile.alarmPolicy() == AlarmPolicy.STRIP) {
            return "Default (no reminders)";
        }
        List<String> parts = new ArrayList<>();
        parts.add(of(profile.target()));
        if (profile.classification() != EventClassification.UNCHANGED) {
            parts.add(of(profile.classification()));
        }
        if (profile.busyStatus() != EventBusyStatus.UNCHANGED) {
            parts.add(of(profile.busyStatus()) + (profile.busyStatusIsDowngraded() ? " (as busy)" : ""));
        }
        parts.add(of(profile.alarmPolicy()));
        return String.join(" · ", parts);
    }

    /** Yes/no rendering shared by every boolean column, so they read the same everywhere. */
    public static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }
}
