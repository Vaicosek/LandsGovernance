package dev.abex.gov.core;

public final class PlayerRecord {
    public boolean vetted;
    /** "auto" or the staff member's name. */
    public String vettedBy;
    /** Salted hash of the IP the account was vetted from; never the IP itself. */
    public String ipHash;
}
