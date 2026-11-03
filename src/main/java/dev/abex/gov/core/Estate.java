package dev.abex.gov.core;
import java.util.*;
public final class Estate {
 public String name;
 public UUID owner;
 public Set<String> chunks = new LinkedHashSet<>();
 public Spot spawn;
 public double balance, spawnFee;
 public List<LedgerEntry> ledger = new ArrayList<>();
 public Set<UUID> trusted = new LinkedHashSet<>();
 public Map<String, Boolean> flags = new LinkedHashMap<>();
 public long created;
}
