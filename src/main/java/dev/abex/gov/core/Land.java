package dev.abex.gov.core;

import java.util.*;

public final class Land {
    public Map<String, Estate> estates = new LinkedHashMap<>();
    public double spawnFee;
    public String name;
    public UUID owner;
    public Map<UUID, String> members = new LinkedHashMap<>();
    public Set<String> chunks = new LinkedHashSet<>();
    public Map<String, UUID> plots = new LinkedHashMap<>();
    public long created;
    public Spot spawn;
    public Map<String, Boolean> flags = new LinkedHashMap<>();
    public double balance;
    public List<LedgerEntry> ledger = new ArrayList<>();
    public int upkeepStrikes;
    public double tax;
    public Map<UUID, Integer> taxStrikes = new LinkedHashMap<>();
    public Set<UUID> banned = new LinkedHashSet<>();

    public static Map<String, Boolean> defaultFlags() {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (String name : List.of("pvp", "monsters", "fire", "explosions", "public", "visitor-interact", "visitor-containers"))
            flags.put(name, name.equals("monsters"));
        return flags;
    }
}
