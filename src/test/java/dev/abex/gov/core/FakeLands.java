package dev.abex.gov.core;

import java.util.*;

/** In-memory stand-in for Lands: one main area per land, roles by name. */
final class FakeLands implements LandsPort {
    static final class FakeLand {
        UUID owner;
        final Map<UUID, String> roles = new LinkedHashMap<>();
    }

    final Map<String, FakeLand> lands = new HashMap<>();
    final List<String> messages = new ArrayList<>();

    FakeLand create(String name, UUID owner) {
        FakeLand land = new FakeLand();
        land.owner = owner;
        land.roles.put(owner, "Owner");
        lands.put(name, land);
        return land;
    }

    void trust(String land, UUID player, String role) {
        lands.get(land).roles.put(player, role);
    }

    @Override public boolean landExists(String land) { return lands.containsKey(land); }
    @Override public UUID owner(String land) { return lands.get(land).owner; }
    @Override public Set<UUID> members(String land) { return Set.copyOf(lands.get(land).roles.keySet()); }
    @Override public Optional<String> roleOf(String land, UUID player) {
        FakeLand l = lands.get(land);
        return l == null ? Optional.empty() : Optional.ofNullable(l.roles.get(player));
    }
    @Override public boolean hasRole(String land, String role) { return true; }
    @Override public void setRole(String land, UUID player, String role) { lands.get(land).roles.put(player, role); }
    @Override public void setOwner(String land, UUID player) {
        FakeLand l = lands.get(land);
        l.roles.put(l.owner, "Citizen");
        l.owner = player;
        l.roles.put(player, "Owner");
    }
    @Override public String name(UUID player) { return player.toString().substring(0, 8); }
    @Override public void announce(String land, String message) { messages.add(land + ": " + message); }
}
