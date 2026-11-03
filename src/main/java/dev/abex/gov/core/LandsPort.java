package dev.abex.gov.core;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** What governance needs from claims; lands are addressed by display name. */
public interface LandsPort {
    boolean landExists(String land);
    UUID owner(String land);
    Set<UUID> members(String land);
    Optional<String> roleOf(String land, UUID player);
    boolean hasRole(String land, String role);
    void setRole(String land, UUID player, String role);
    void setOwner(String land, UUID player);
    String name(UUID player);
    /** Tell every member of the land (online now; offline ones on join is a later feature). */
    void announce(String land, String message);
}
