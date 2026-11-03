package dev.abex.gov.core;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class NativeLands implements LandsPort {
    private final LandBook book;
    private final Function<UUID, String> names;
    private final BiConsumer<String, String> announcer;

    public NativeLands(LandBook book, Function<UUID, String> names, BiConsumer<String, String> announcer) {
        this.book = book;
        this.names = names;
        this.announcer = announcer;
    }

    public boolean landExists(String land) { return book.land(land).isPresent(); }
    public UUID owner(String land) { return book.land(land).map(l -> l.owner).orElse(null); }
    public Set<UUID> members(String land) { return book.land(land).map(l -> Set.copyOf(l.members.keySet())).orElse(Set.of()); }
    public Optional<String> roleOf(String land, UUID player) { return book.land(land).map(l -> l.members.get(player)); }
    public boolean hasRole(String land, String role) { return landExists(land) && book.roles().exists(role) != null; }
    public void setRole(String land, UUID player, String role) {
        String canonical = book.roles().exists(role);
        book.land(land).filter(l -> l.members.containsKey(player) && canonical != null)
                .ifPresent(l -> l.members.put(player, canonical));
    }
    public void setOwner(String land, UUID player) {
        book.land(land).filter(l -> l.members.containsKey(player)).ifPresent(l -> book.changeOwner(l, player));
    }
    public String name(UUID player) {
        String name = names.apply(player);
        return name == null ? player.toString() : name;
    }
    public void announce(String land, String message) {
        announcer.accept(book.land(land).map(l -> l.name).orElse(land), message);
    }
}
