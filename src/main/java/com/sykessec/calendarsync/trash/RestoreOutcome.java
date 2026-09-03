package com.sykessec.calendarsync.trash;

/** success=false carries a message meant to be surfaced to the user, not just logged. */
public record RestoreOutcome(boolean success, String message) {

    public static RestoreOutcome ok() {
        return new RestoreOutcome(true, null);
    }

    public static RestoreOutcome failed(String message) {
        return new RestoreOutcome(false, message);
    }
}
