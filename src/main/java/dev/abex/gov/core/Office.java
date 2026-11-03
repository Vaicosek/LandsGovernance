package dev.abex.gov.core;

import java.util.UUID;

/** An elected seat: who holds it, what role they had before, when the term ends. */
public final class Office {
    public UUID holder;
    public String previousRole;
    public long since;
    public long termEnds;
}
