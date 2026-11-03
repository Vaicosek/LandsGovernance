package dev.abex.gov.core;

import java.util.*;

public final class RoleSet {
    private final LinkedHashMap<String, Set<Perm>> roles = new LinkedHashMap<>();

    private RoleSet(LinkedHashMap<String, Set<Perm>> source) {
        if (source.size() < 2) throw new IllegalArgumentException("Configure at least two roles.");
        source.forEach((name, perms) -> {
            if (name.isBlank() || exists(name) != null) throw new IllegalArgumentException("Duplicate or empty role: " + name);
            roles.put(name, Set.copyOf(perms));
        });
    }

    public static RoleSet of(LinkedHashMap<String, Set<Perm>> roles) { return new RoleSet(roles); }
    public String exists(String role) {
        return roles.keySet().stream().filter(r -> r.equalsIgnoreCase(role)).findFirst().orElse(null);
    }
    public boolean has(String role, Perm perm) { return roles.getOrDefault(exists(role), Set.of()).contains(perm); }
    public int rank(String role) { String r = role == null ? null : exists(role); return r == null ? -1 : names().indexOf(r); }
    public List<String> names() { return List.copyOf(roles.keySet()); }
    public String top() { return names().getFirst(); }
    public String belowTop() { return names().get(1); }
    public String lowest() { return names().getLast(); }
}
