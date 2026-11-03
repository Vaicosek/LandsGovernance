package dev.abex.gov.core;

import java.util.*;

/** Everything the plugin stores; saved as one JSON file. */
public final class GovData {
    public Map<String, Land> lands = new LinkedHashMap<>();
    public Map<String, Long> invites = new HashMap<>();
    public Map<UUID, PlayerRecord> players = new HashMap<>();
    /** land|uuid -> since when the player has continuously held a voting role there. */
    public Map<String, Long> votingSince = new HashMap<>();
    public Map<String, Election> elections = new LinkedHashMap<>();
    /** land|role -> elected seat. */
    public Map<String, Office> offices = new HashMap<>();
    public Map<String, Charter> charters = new HashMap<>();
    public Map<String, Realm> realms = new LinkedHashMap<>();
    public Map<String, Long> realmInvites = new HashMap<>();
    public Map<String, RealmRelation> realmRelations = new LinkedHashMap<>();
    /** Separate election namespace; initialized only for the root data by RealmService. */
    public GovData realmGovernance;
    public Long lastRealmDay;
    public Map<UUID, Double> pendingRefunds = new HashMap<>();
    /** world:x:y:z of the chest -> shop. */
    public Map<String, Shop> shops = new LinkedHashMap<>();
    /** uuid|tip for one-time tutorial tips already shown. */
    public Set<String> tipsSeen = new HashSet<>();
    public List<Sale> sales = new ArrayList<>();
    public List<SaleFeed.Pending> saleFeed = new ArrayList<>();
    public long nextSaleId = 1;
    /** Viewer -> last time they read their shop log. */
    public Map<UUID, Long> seen = new HashMap<>();
    /** Event watermark prevents same-millisecond trades being lost after a read. */
    public Map<UUID, Long> salesSeenId = new HashMap<>();
    public int nextId = 1;
    public String lastEconomyDay;
    /** Random per server; IPs are stored only as sha256(salt + ip). */
    public String salt;
    /** Set after the first tenure sweep, which grandfathers members who were there before the plugin. */
    public boolean bootstrapped;

    public static String key(String land, Object part) {
        return land + "|" + part;
    }

    public Optional<Election> openElection(String land, String role) {
        return elections.values().stream()
                .filter(e -> e.state == Election.State.OPEN && e.land.equalsIgnoreCase(land) && e.role.equalsIgnoreCase(role))
                .findFirst();
    }

    public List<Election> openElections(String land) {
        return elections.values().stream()
                .filter(e -> e.state == Election.State.OPEN && e.land.equalsIgnoreCase(land))
                .toList();
    }
}
