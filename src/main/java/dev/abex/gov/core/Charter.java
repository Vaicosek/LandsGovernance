package dev.abex.gov.core;

public final class Charter {
    public enum Mode { NONE, COUNCIL, REPUBLIC }

    public Mode mode = Mode.NONE;
    public long signedAt;
    /** When a pending revoke takes effect; 0 = none pending. */
    public long revokeAt;

    public Charter() {
    }

    public Charter(Mode mode, long signedAt) {
        this.mode = mode;
        this.signedAt = signedAt;
    }
}
