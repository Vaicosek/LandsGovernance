package dev.abex.gov.core;
import java.util.*;
/** Realm city membership is timestamped; city membership itself remains authoritative. */
public final class Realm {
 public String name, capital;
 public UUID ruler;
 public Map<String,Long> cities=new LinkedHashMap<>();
 public Map<UUID,String> roles=new LinkedHashMap<>();
 public Map<String,Integer> unpaid=new HashMap<>();
 public Map<String,Double> withdrawn=new HashMap<>();
 public double balance, tax, spawnFee;
 public boolean publicSpawn;
 public Spot spawn;
 public List<LedgerEntry> ledger=new ArrayList<>();
}
