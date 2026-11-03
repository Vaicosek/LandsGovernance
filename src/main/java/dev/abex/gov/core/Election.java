package dev.abex.gov.core;

import java.util.*;

public final class Election {
    public enum State { OPEN, CLOSED, NO_QUORUM, CANCELLED, VOIDED }

    public String id;
    public String land;
    public String role;
    public long opens;
    public long closes;
    public State state = State.OPEN;
    public List<UUID> candidates = new ArrayList<>();
    /** voter -> candidate; a voter can change their vote while open. */
    public Map<UUID, UUID> votes = new LinkedHashMap<>();
    public UUID winner;

    public Election() {
    }

    public Election(String id, String land, String role, long opens, long closes) {
        this.id = id;
        this.land = land;
        this.role = role;
        this.opens = opens;
        this.closes = closes;
    }

    public Map<UUID, Integer> tally() {
        Map<UUID, Integer> t = new LinkedHashMap<>();
        for (UUID c : candidates) t.put(c, 0);
        for (UUID c : votes.values()) t.merge(c, 1, Integer::sum);
        return t;
    }
}
