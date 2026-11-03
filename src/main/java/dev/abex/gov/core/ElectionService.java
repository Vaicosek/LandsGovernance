package dev.abex.gov.core;

import java.util.*;

/**
 * All governance rules: vetting, tenure, elections, charters and the abuse blocks.
 * Plain Java; the Bukkit side only feeds it events and the clock.
 */
public class ElectionService {
    public static final String OWNER_OFFICE = "Owner";
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;

    private final GovData data;
    private final LandsPort lands;
    private final Settings s;

    public ElectionService(GovData data, LandsPort lands, Settings settings) {
        this.data = data;
        this.lands = lands;
        this.s = settings;
    }

    public GovData data() {
        return data;
    }

    public Settings settings() {
        return s;
    }

    private String canonicalLand(String name) {
        Land land = data.lands.get(name.toLowerCase(Locale.ROOT));
        return land == null ? name : land.name;
    }

    public Guard guard() {
        return new Guard() {
            public boolean blockKick(String land, UUID target, long now) { return blockUntrust(land, target, now); }
            public boolean blockDelete(String land) { return ElectionService.this.blockDelete(land, false); }
            public boolean blockTransfer(String land) { return blockOwnerChange(land); }
            public boolean plotsLocked(String land) { return charter(land).mode != Charter.Mode.NONE; }
        };
    }

    // --- vetting ---

    public Result tryVet(UUID p, long playtimeMinutes, String ipHash) {
        PlayerRecord r = data.players.computeIfAbsent(p, k -> new PlayerRecord());
        if (r.vetted) return Result.ok("You are already vetted.");
        long need = s.minPlaytimeHours() * 60L;
        if (playtimeMinutes < need) {
            return Result.no("Vetting needs " + s.minPlaytimeHours() + " h playtime (you have "
                    + playtimeMinutes / 60 + " h).");
        }
        if (s.oneVettedPerIp() && ipHash != null && data.players.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(p) && e.getValue().vetted && ipHash.equals(e.getValue().ipHash))) {
            return Result.no("Your connection is already used by another vetted account. Shared house? Ask staff.");
        }
        r.vetted = true;
        r.vettedBy = "auto";
        r.ipHash = ipHash;
        return Result.ok("You are vetted: you can now vote and run in your cities.");
    }

    public void setVetted(UUID p, boolean vetted, String by) {
        PlayerRecord r = data.players.computeIfAbsent(p, k -> new PlayerRecord());
        r.vetted = vetted;
        r.vettedBy = vetted ? by : null;
    }

    public boolean isVetted(UUID p) {
        PlayerRecord r = data.players.get(p);
        return r != null && r.vetted;
    }

    // --- tenure ---

    /** Record the player's current role in the land; tenure runs while they keep any voting role. */
    public void observe(String landName, UUID p, long now) {
        String land = canonicalLand(landName);
        String k = GovData.key(land, p);
        Optional<String> role = lands.landExists(land) ? lands.roleOf(land, p) : Optional.empty();
        if (role.isPresent() && isVotingRole(role.get())) {
            data.votingSince.putIfAbsent(k, now);
        } else {
            data.votingSince.remove(k);
        }
    }

    /**
     * Refresh tenure for every member of the land and forget ex-members, so leaving and rejoining starts over.
     * grandfather = first sweep after install: members already holding a voting role count as fully tenured.
     */
    public void sweep(String landName, long now, boolean grandfather) {
        String land = canonicalLand(landName);
        if (!lands.landExists(land)) return;
        Set<UUID> members = lands.members(land);
        String prefix = land + "|";
        data.votingSince.keySet().removeIf(k -> k.startsWith(prefix)
                && !members.contains(UUID.fromString(k.substring(prefix.length()))));
        long start = grandfather ? now - s.tenureDays() * DAY : now;
        for (UUID p : members) observe(land, p, start);
    }

    private boolean isVotingRole(String role) {
        return s.votingRoles().stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    /** Whole days the player has held a voting role in the land; -1 if none. */
    public long tenureDays(String landName, UUID p, long now) {
        String land = canonicalLand(landName);
        Long since = data.votingSince.get(GovData.key(land, p));
        return since == null ? -1 : (now - since) / DAY;
    }

    public Result eligibility(String landName, UUID p, long now) {
        String land = canonicalLand(landName);
        if (!isVetted(p)) return Result.no("You are not vetted yet. Run /c vet.");
        if (!lands.landExists(land) || !lands.members(land).contains(p)) {
            return Result.no("You are not a member of " + land + ".");
        }
        Long since = data.votingSince.get(GovData.key(land, p));
        if (since == null || now - since < s.tenureDays() * DAY) {
            long have = since == null ? 0 : (now - since) / DAY;
            return Result.no("You need a voting role in " + land + " for " + s.tenureDays()
                    + " days (you have " + have + ").");
        }
        return Result.ok("eligible");
    }

    // --- elections ---

    public Result startElection(String landName, UUID actor, String role, int hours, long now) {
        String land = canonicalLand(landName);
        if (!lands.landExists(land)) return Result.no("No city called " + land + ".");
        if (!actor.equals(lands.owner(land))) return Result.no("Only the owner of " + land + " can open an election.");
        return open(land, role, hours, now);
    }

    private Result open(String landName, String role, int hours, long now) {
        String land = canonicalLand(landName);
        boolean ownerOffice = role.equalsIgnoreCase(OWNER_OFFICE);
        if (ownerOffice && charter(land).mode != Charter.Mode.REPUBLIC) {
            return Result.no("Electing the owner needs a republic charter (/c charter sign republic).");
        }
        if (!ownerOffice && !lands.hasRole(land, role)) return Result.no(land + " has no role called " + role + ".");
        if (data.openElection(land, role).isPresent()) return Result.no("There is already an open election for " + role + ".");
        int h = hours > 0 ? hours : s.defaultElectionHours();
        String id = String.valueOf(data.nextId++);
        Election e = new Election(id, land, ownerOffice ? OWNER_OFFICE : role, now, now + h * HOUR);
        data.elections.put(id, e);
        lands.announce(land, "An election for " + e.role + " is open for " + h + " h. /c run to stand, /c vote to vote.");
        return Result.ok("Election for " + e.role + " opened (" + h + " h).", id);
    }

    public Result run(String landName, UUID p, long now) {
        String land = canonicalLand(landName);
        return run(land, null, p, now);
    }

    public Result run(String landName, String role, UUID p, long now) {
        String land = canonicalLand(landName);
        List<Election> open = data.openElections(land);
        if (role != null) open = open.stream().filter(e -> e.role.equalsIgnoreCase(role)).toList();
        if (open.isEmpty()) return Result.no("No open election in " + land + ".");
        if (open.size() > 1) return Result.no("Several elections are open; say which: /c run <role>.");
        Result elig = eligibility(land, p, now);
        if (!elig.ok()) return elig;
        Election e = open.getFirst();
        if (e.candidates.contains(p)) return Result.no("You are already standing for " + e.role + ".");
        e.candidates.add(p);
        lands.announce(land, lands.name(p) + " is standing for " + e.role + ".");
        return Result.ok("You are standing for " + e.role + ".");
    }

    public Result vote(String landName, UUID voter, UUID candidate, long now) {
        String land = canonicalLand(landName);
        Optional<Election> found = data.openElections(land).stream()
                .filter(e -> e.candidates.contains(candidate)).findFirst();
        if (found.isEmpty()) return Result.no("That player isn't standing in an open election in " + land + ".");
        Result elig = eligibility(land, voter, now);
        if (!elig.ok()) return elig;
        Election e = found.get();
        UUID before = e.votes.put(voter, candidate);
        return Result.ok((before == null ? "Voted for " : "Changed your vote to ") + lands.name(candidate)
                + " as " + e.role + ".");
    }

    public Result cancel(String landName, UUID actor) {
        String land = canonicalLand(landName);
        if (!lands.landExists(land) || !actor.equals(lands.owner(land))) {
            return Result.no("Only the owner can cancel elections.");
        }
        if (charter(land).mode != Charter.Mode.NONE) {
            return Result.no(land + "'s charter forbids cancelling elections. Revoking it takes "
                    + s.charterRevokeDays() + " days.");
        }
        List<Election> open = data.openElections(land);
        if (open.isEmpty()) return Result.no("No open election to cancel.");
        for (Election e : open) {
            e.state = Election.State.CANCELLED;
            lands.announce(land, "The owner " + lands.name(actor) + " cancelled the election for " + e.role + ".");
        }
        return Result.ok("Cancelled " + open.size() + " election(s). Every member was told.");
    }

    /** Admin: close the land's open elections now, counting votes as they stand. */
    public Result closeNow(String landName, long now) {
        String land = canonicalLand(landName);
        List<Election> open = data.openElections(land);
        if (open.isEmpty()) return Result.no("No open election in " + land + ".");
        for (Election e : open) close(e, now);
        return Result.ok("Closed " + open.size() + " election(s).");
    }

    /** Close due elections, apply charter revokes, reopen elections whose term ran out. Run every minute. */
    public void tick(long now) {
        for (Election e : List.copyOf(data.elections.values())) {
            if (e.state == Election.State.OPEN && now >= e.closes) close(e, now);
        }
        for (Map.Entry<String, Charter> en : data.charters.entrySet()) {
            Charter c = en.getValue();
            if (c.revokeAt > 0 && now >= c.revokeAt) {
                c.mode = Charter.Mode.NONE;
                c.revokeAt = 0;
                if (lands.landExists(en.getKey())) {
                    lands.announce(en.getKey(), "The charter has ended. The owner rules alone again.");
                }
            }
        }
        for (Map.Entry<String, Office> en : data.offices.entrySet()) {
            String[] k = en.getKey().split("\\|", 2);
            String land = k[0], role = k[1];
            Office o = en.getValue();
            if (!lands.landExists(land) || charter(land).mode == Charter.Mode.NONE || now < o.termEnds) continue;
            if (data.openElection(land, role).isPresent()) continue;
            Result r = open(land, role, 0, now);
            if (r.ok()) o.termEnds = data.elections.get(r.value()).closes + s.termDays() * DAY;
        }
    }

    /** Specializations can use candidacy order without altering city election tie rules. */
    protected long candidateSeniority(Election e, UUID candidate) {
        return data.votingSince.getOrDefault(GovData.key(e.land, candidate), Long.MAX_VALUE);
    }

    private void close(Election e, long now) {
        if (e.votes.size() < s.minVoters()) {
            e.state = Election.State.NO_QUORUM;
            lands.announce(e.land, "The election for " + e.role + " closed without enough voters (" + e.votes.size()
                    + " of " + s.minVoters() + "). Nothing changes.");
            return;
        }
        Map<UUID, Integer> tally = e.tally();
        UUID winner = e.candidates.stream()
                .max(Comparator.<UUID>comparingInt(tally::get)
                        .thenComparing(c -> -candidateSeniority(e, c))
                        .thenComparing(c -> -e.candidates.indexOf(c)))
                .orElse(null);
        // Close before applying the result so ownership changes see the completed election.
        e.state = Election.State.CLOSED;
        e.winner = winner;
        if (winner == null || !lands.landExists(e.land)) return;
        applyWin(e, winner, now);
        lands.announce(e.land, lands.name(winner) + " won the election for " + e.role + " with " + tally.get(winner)
                + " of " + e.votes.size() + " votes.");
    }

    private void applyWin(Election e, UUID winner, long now) {
        String k = GovData.key(e.land, e.role);
        Office prev = data.offices.get(k);
        Office o = new Office();
        o.holder = winner;
        o.since = now;
        o.termEnds = now + s.termDays() * DAY;
        if (e.role.equals(OWNER_OFFICE)) {
            if (!winner.equals(lands.owner(e.land))) lands.setOwner(e.land, winner);
        } else {
            if (prev != null && prev.holder != null && !prev.holder.equals(winner)
                    && lands.members(e.land).contains(prev.holder)
                    && lands.roleOf(e.land, prev.holder).map(r -> r.equalsIgnoreCase(e.role)).orElse(false)) {
                lands.setRole(e.land, prev.holder, prev.previousRole);
            }
            o.previousRole = prev != null && winner.equals(prev.holder)
                    ? prev.previousRole
                    : lands.roleOf(e.land, winner).orElse(null);
            lands.setRole(e.land, winner, e.role);
        }
        data.offices.put(k, o);
    }

    // --- abuse blocks ---

    /** True = cancel the delete. Staff, upkeep and war deletes go through and void the elections. */
    public boolean blockDelete(String landName, boolean staffOrSystem) {
        String land = canonicalLand(landName);
        List<Election> open = data.openElections(land);
        if (open.isEmpty()) return false;
        if (!staffOrSystem) return true;
        for (Election e : open) e.state = Election.State.VOIDED;
        return false;
    }

    public boolean blockUntrust(String landName, UUID target, long now) {
        String land = canonicalLand(landName);
        List<Election> open = data.openElections(land);
        if (open.isEmpty()) return false;
        return eligibility(land, target, now).ok() || open.stream().anyMatch(e -> e.candidates.contains(target));
    }

    public boolean blockOwnerChange(String landName) {
        String land = canonicalLand(landName);
        return !data.openElections(land).isEmpty();
    }

    // --- charter ---

    public Charter charter(String landName) {
        String land = canonicalLand(landName);
        return data.charters.getOrDefault(land, new Charter());
    }

    public Result signCharter(String landName, UUID actor, Charter.Mode mode, long now) {
        String land = canonicalLand(landName);
        if (!lands.landExists(land) || !actor.equals(lands.owner(land))) return Result.no("Only the owner can sign the charter.");
        if (mode == Charter.Mode.NONE) return Result.no("To leave a charter use /c charter revoke.");
        Charter c = charter(land);
        if (c.mode == mode) return Result.no(land + " already has a " + name(mode) + " charter.");
        if (c.mode == Charter.Mode.REPUBLIC) return Result.no("A republic can only step down through /c charter revoke.");
        data.charters.put(land, new Charter(mode, now));
        lands.announce(land, "The owner signed a " + name(mode) + " charter: elections can't be cancelled"
                + (mode == Charter.Mode.REPUBLIC ? ", and the owner is elected too" : "")
                + ". Revoking it takes " + s.charterRevokeDays() + " days.");
        return Result.ok("Charter signed: " + name(mode) + ".");
    }

    public Result revokeCharter(String landName, UUID actor, long now) {
        String land = canonicalLand(landName);
        if (!lands.landExists(land) || !actor.equals(lands.owner(land))) return Result.no("Only the owner can revoke the charter.");
        Charter c = charter(land);
        if (c.mode == Charter.Mode.NONE) return Result.no(land + " has no charter.");
        if (c.revokeAt > 0) return Result.no("Revoking is already under way.");
        c.revokeAt = now + s.charterRevokeDays() * DAY;
        data.charters.put(land, c);
        lands.announce(land, "The owner started revoking the " + name(c.mode) + " charter. It ends in "
                + s.charterRevokeDays() + " days.");
        return Result.ok("Charter ends in " + s.charterRevokeDays() + " days. Members were told.");
    }

    private static String name(Charter.Mode m) {
        return m.name().toLowerCase(Locale.ROOT);
    }
}
