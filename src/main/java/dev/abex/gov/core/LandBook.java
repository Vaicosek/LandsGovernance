package dev.abex.gov.core;

import java.util.*;

public final class LandBook {
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(LandBook.class.getName());
    private final GovData data;
    private final RoleSet roles;
    private final ClaimSettings settings;
    private final Map<String, String> chunks = new HashMap<>();
    private final Map<String, Boolean> flagDefaults = Land.defaultFlags();
    private RealmService realms;
    public void setRealms(RealmService realms) { this.realms = realms; }
    public RealmService realms() { return realms; }
    private EconomyPort economy;
    public void setEconomy(EconomyPort economy) { this.economy = economy; }
    private Guard guard = Guard.NONE;

    public LandBook(GovData data, RoleSet roles, ClaimSettings settings) {
        this(data, roles, settings, Land.defaultFlags());
    }

    public LandBook(GovData data, RoleSet roles, ClaimSettings settings, Map<String, Boolean> defaults) {
        for (String flag : flagDefaults.keySet()) flagDefaults.put(flag, defaults.getOrDefault(flag, flagDefaults.get(flag)));
        this.data = data;
        this.roles = roles;
        this.settings = settings;
        for (Land land : data.lands.values()) {
            flagDefaults.forEach(land.flags::putIfAbsent);
            for (String chunk : land.chunks) chunks.put(chunk, key(land.name));
        }
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    public RoleSet roles() { return roles; }
    public void setGuard(Guard guard) { this.guard = Objects.requireNonNull(guard); }
    public Optional<Land> land(String name) { return Optional.ofNullable(data.lands.get(key(name))); }
    public Optional<Land> landAt(ChunkPos chunk) { return Optional.ofNullable(data.lands.get(chunks.get(chunk.key()))); }
    public boolean has(Land land, UUID actor, Perm perm) {
        return land != null && roles.has(land.members.get(actor), perm);
    }
    private boolean outranks(Land land, UUID actor, UUID target) {
        int a = roles.rank(land.members.get(actor)), t = roles.rank(land.members.get(target));
        return a >= 0 && t > a;
    }

    public Result create(UUID actor, String name, ChunkPos chunk, long now) {
        if (name == null || !name.matches("[A-Za-z0-9_]{3,24}")) return Result.no("Use 3-24 letters, numbers or underscores.");
        if (land(name).isPresent() || estate(name).isPresent()) return Result.no("That city name is taken.");
        if (landAt(chunk).isPresent()) return Result.no("This chunk is already claimed.");
        if (data.lands.values().stream().anyMatch(l -> actor.equals(l.owner))) return Result.no("You already own a city.");
        Land land = new Land();
        land.name = name;
        land.owner = actor;
        land.created = now;
        land.flags.putAll(flagDefaults);
        land.members.put(actor, roles.top());
        land.chunks.add(chunk.key());
        data.lands.put(key(name), land);
        chunks.put(chunk.key(), key(name));
        return Result.ok("Created " + name + ".");
    }

    public Result claim(UUID actor, ChunkPos chunk) {
        if (landAt(chunk).isPresent()) return Result.no("This chunk is already claimed.");
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : offsets) {
            Land land = landAt(new ChunkPos(chunk.world(), chunk.x() + offset[0], chunk.z() + offset[1])).orElse(null);
            if (!has(land, actor, Perm.CLAIM)) continue;
            long limit = settings.baseChunks() + (long) settings.chunksPerMember() * land.members.size();
            if (land.chunks.size() >= limit) continue;
            land.chunks.add(chunk.key());
            chunks.put(chunk.key(), key(land.name));
            return Result.ok("Claimed for " + land.name + ".");
        }
        return Result.no("Touch a city where you can claim and have chunks available.");
    }

    public Result unclaim(UUID actor, ChunkPos chunk) {
        Land land = landAt(chunk).orElse(null);
        if (!has(land, actor, Perm.CLAIM)) return Result.no("You can't unclaim here.");
        if (estateAt(chunk).isPresent()) return Result.no("Remove this chunk from its estate first.");
        if ((land.spawn != null && land.spawn.chunk().equals(chunk)) || (realms != null && realms.spawnChunk(chunk))) return Result.no("Move the spawn before unclaiming this chunk.");
        if (land.chunks.size() == 1) return Result.no("Keep the last chunk, or delete the city.");
        land.chunks.remove(chunk.key());
        land.plots.remove(chunk.key());
        chunks.remove(chunk.key());
        return Result.ok("Chunk unclaimed.");
    }

    public Result invite(UUID actor, String name, UUID target, long now) {
        Land land = land(name).orElse(null);
        if (!has(land, actor, Perm.INVITE)) return Result.no("You can't invite members here.");
        if (land.banned.contains(target)) return Result.no("Unban that player first.");
        if (land.members.containsKey(target)) return Result.no("That player is already a member.");
        data.invites.put(key(name) + "|" + target, now + 86_400_000L);
        return Result.ok("Invited for 24 hours.");
    }

    public Result join(UUID actor, String name, long now) {
        Land land = land(name).orElse(null);
        String invite = key(name) + "|" + actor;
        if (land == null || data.invites.getOrDefault(invite, Long.MIN_VALUE) <= now) return Result.no("You need a valid invite.");
        if (land.banned.contains(actor)) return Result.no("You are banned from this city.");
        if (land.members.containsKey(actor)) return Result.no("You are already a member.");
        land.members.put(actor, roles.lowest());
        data.invites.remove(invite);
        return Result.ok("Joined " + land.name + ".");
    }

    public Result kick(UUID actor, String name, UUID target, long now) {
        Land land = land(name).orElse(null);
        if (!has(land, actor, Perm.KICK) || !outranks(land, actor, target)) return Result.no("You must have KICK and outrank that member.");
        if (guard.blockKick(land.name, target, now)) return Result.no("That member is protected during the election.");
        if (!removeMember(land, target, now)) return Result.no("Estate payout failed; membership retained.");
        return Result.ok("Member removed.");
    }

    public Result setRole(UUID actor, String name, UUID target, String role) {
        Land land = land(name).orElse(null);
        String canonical = roles.exists(role);
        if (!has(land, actor, Perm.SET_ROLES) || !outranks(land, actor, target)) return Result.no("You must have SET_ROLES and outrank that member.");
        if (canonical == null || roles.rank(canonical) <= roles.rank(land.members.get(actor))) return Result.no("Choose an existing role below yours.");
        land.members.put(target, canonical);
        return Result.ok("Role set to " + canonical + ".");
    }

    public Result givePlot(UUID actor, ChunkPos chunk, UUID target) {
        Land land = landAt(chunk).orElse(null);
        if (!has(land, actor, Perm.PLOTS)) return Result.no("You can't give lots here.");
        if (!land.members.containsKey(target)) return Result.no("The lot owner must be a member.");
        if (estateAt(chunk).isPresent()) return Result.no("Use estate commands here.");
        UUID previous = land.plots.get(chunk.key());
        if (previous != null && !previous.equals(target) && guard.plotsLocked(land.name)) return Result.no("The lot owner must give up this lot first.");
        land.plots.put(chunk.key(), target);
        return Result.ok("Lot assigned.");
    }

    public Result revokePlot(UUID actor, ChunkPos chunk) {
        Land land = landAt(chunk).orElse(null);
        if (land == null || !land.plots.containsKey(chunk.key())) return Result.no("There is no lot here.");
        if (estateAt(chunk).isPresent()) return Result.no("Use estate commands here.");
        boolean owner = actor.equals(land.plots.get(chunk.key()));
        if (!owner && (!has(land, actor, Perm.PLOTS) || guard.plotsLocked(land.name))) return Result.no("You can't revoke this lot.");
        land.plots.remove(chunk.key());
        return Result.ok("Lot revoked.");
    }

    public long plotsOf(String name, UUID player) {
        return land(name).map(l -> l.plots.values().stream().filter(player::equals).count()).orElse(0L);
    }

    public boolean can(UUID player, ChunkPos chunk, Perm perm) {
        Land land = landAt(chunk).orElse(null);
        if (land == null) return true;
        if (land.banned.contains(player)) return false;
        Estate estate = estateAt(chunk).orElse(null);
        if (estate != null && (perm == Perm.BUILD || perm == Perm.CONTAINERS))
            return estate.owner.equals(player) || estate.trusted.contains(player);
        UUID plot = land.plots.get(chunk.key());
        if (plot != null && (perm == Perm.BUILD || perm == Perm.CONTAINERS)) return plot.equals(player);
        if (realms != null && realms.allyPermission(player, land, perm, System.currentTimeMillis())) return true;
        if (!land.members.containsKey(player)) {
            if (perm == Perm.INTERACT) return can(chunk, "visitor-interact");
            if (perm == Perm.CONTAINERS) return can(chunk, "visitor-containers");
        }
        return has(land, player, perm);
    }

    public boolean sameClaim(ChunkPos a, ChunkPos b) {
        if (!Objects.equals(chunks.get(a.key()), chunks.get(b.key()))) return false;
        Land land = landAt(a).orElse(null);
        return land == null || (Objects.equals(estateAt(a).orElse(null), estateAt(b).orElse(null))
                && Objects.equals(land.plots.get(a.key()), land.plots.get(b.key())));
    }

    private boolean removeMember(Land land, UUID player, long now) {
        for (Estate estate : new ArrayList<>(land.estates.values()))
            if (estate.owner.equals(player) && !dissolve(land, estate, now)) return false;
        land.members.remove(player);
        land.taxStrikes.remove(player);
        land.plots.values().removeIf(player::equals);
        data.votingSince.keySet().removeIf(k -> k.equalsIgnoreCase(GovData.key(land.name, player)));
        return true;
    }

    public Result leave(UUID actor, String name) {
        return leave(actor, name, System.currentTimeMillis());
    }

    public Result leave(UUID actor, String name, long now) {
        Land land = land(name).orElse(null);
        if (land == null || !land.members.containsKey(actor)) return Result.no("You aren't a member here.");
        if (actor.equals(land.owner)) return Result.no("Hand over ownership or delete the city first.");
        if (!removeMember(land, actor, now)) return Result.no("Estate payout failed; membership retained.");
        return Result.ok("Left " + land.name + ".");
    }

    public Result delete(UUID actor, String name, long now) {
        Land land = land(name).orElse(null);
        if (land == null || !actor.equals(land.owner)) return Result.no("Only the owner can delete this city.");
        if (guard.blockDelete(land.name)) return Result.no("You can't delete during an election.");
        return removeLand(land, false, now);
    }

    private Result removeLand(Land land, boolean admin, long now) {
        for (Estate estate : new ArrayList<>(land.estates.values()))
            if (!dissolve(land, estate, now)) return Result.no("Estate payout failed; city retained.");
        if (realms != null) {
            Result realmResult = realms.deletingCity(land, now);
            if (!realmResult.ok()) return realmResult;
        }
        String name = land.name;
        land.chunks.forEach(chunks::remove);
        data.lands.remove(key(name));
        String prefix = key(name) + "|";
        data.invites.keySet().removeIf(k -> k.startsWith(prefix));
        data.votingSince.keySet().removeIf(k -> key(k).startsWith(prefix));
        data.offices.keySet().removeIf(k -> key(k).startsWith(prefix));
        data.charters.keySet().removeIf(k -> k.equalsIgnoreCase(name));
        if (!admin) data.elections.values().removeIf(e -> e.land.equalsIgnoreCase(name));
        return Result.ok("Deleted " + land.name + ".");
    }

    public Result transfer(UUID actor, String name, UUID target, long now) {
        Land land = land(name).orElse(null);
        if (land == null || !actor.equals(land.owner)) return Result.no("Only the owner can hand over this city.");
        if (!land.members.containsKey(target)) return Result.no("The new owner must be a member.");
        if (guard.blockTransfer(land.name)) return Result.no("Ownership can't change during an election.");
        if (data.lands.values().stream().anyMatch(l -> l != land && target.equals(l.owner))) return Result.no("That player already owns a city.");
        changeOwner(land, target);
        return Result.ok("Ownership transferred.");
    }

    void changeOwner(Land land, UUID target) {
        if (target.equals(land.owner)) return;
        land.members.put(land.owner, roles.belowTop());
        land.owner = target;
        land.members.put(target, roles.top());
    }

    public Result create(UUID actor, String name, Spot spot, long now) {
        Result result = create(actor, name, spot.chunk(), now);
        if (result.ok()) land(name).orElseThrow().spawn = spot;
        return result;
    }

    public Result setSpawn(UUID actor, String name, Spot spot) {
        Land land = land(name).orElse(null);
        if (!has(land, actor, Perm.SETTINGS)) return Result.no("You can't set the spawn here.");
        if (landAt(spot.chunk()).orElse(null) != land) return Result.no("Stand inside this city.");
        land.spawn = spot;
        return Result.ok("Spawn set.");
    }

    public Result spawn(UUID actor, String name) {
        Estate estate = estate(name).orElse(null);
        Land land = placeCity(name);
        if (land != null && realms != null && realms.ofCity(land.name).map(r -> realms.enemy(actor, r, System.currentTimeMillis())).orElse(false))
            return Result.no("Enemies cannot use this spawn.");
        boolean friendly = land != null && realms != null && realms.ofCity(land.name).map(r -> realms.friendly(actor, r, System.currentTimeMillis())).orElse(false);
        if (estate != null) {
            if (land.banned.contains(actor)) return Result.no("You are banned from this city.");
            return estate.spawn != null && (friendly || land.members.containsKey(actor) || land.flags.getOrDefault("public", false) || estate.flags.getOrDefault("public", false))
                    ? Result.ok("Travelling to " + estate.name + ".") : Result.no("This spawn is for members.");
        }
        if (land == null || land.spawn == null) return Result.no("This city has no spawn yet.");
        if (land.banned.contains(actor)) return Result.no("You are banned from this city.");
        if (!friendly && !land.members.containsKey(actor) && !land.flags.getOrDefault("public", false)) return Result.no("This spawn is for members.");
        return Result.ok("Travelling to " + land.name + ".");
    }

    public boolean can(ChunkPos chunk, String flag) {
        if (!flagDefaults.containsKey(flag)) return false;
        Land land = landAt(chunk).orElse(null);
        return land == null || land.flags.getOrDefault(flag, flagDefaults.get(flag));
    }

    public Result flag(UUID actor, String name, String flag, boolean enabled) {
        Land land = land(name).orElse(null);
        if (!has(land, actor, Perm.SETTINGS)) return Result.no("You can't change flags here.");
        if (!flagDefaults.containsKey(flag)) return Result.no("Unknown flag.");
        land.flags.put(flag, enabled);
        return Result.ok(flag + " is " + (enabled ? "on." : "off."));
    }

    public boolean touches(UUID actor, ChunkPos chunk) {
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : offsets) {
            Land land = landAt(new ChunkPos(chunk.world(), chunk.x() + offset[0], chunk.z() + offset[1])).orElse(null);
            if (has(land, actor, Perm.CLAIM)) return true;
        }
        return false;
    }

    public Result claimAt(UUID actor, ChunkPos from, ChunkPos target) {
        if (!from.world().equals(target.world()) || Math.abs((long) from.x() - target.x()) > 8
                || Math.abs((long) from.z() - target.z()) > 8) return Result.no("Move within 8 chunks first.");
        return claim(actor, target);
    }

    public Result tax(UUID actor, String name, double amount, double maximum) {
        Land land = land(name).orElse(null);
        if (!has(land, actor, Perm.SETTINGS)) return Result.no("You can't set taxes here.");
        if (!Double.isFinite(amount) || amount < 0 || amount > maximum) return Result.no("Choose a tax from 0 to " + maximum + ".");
        land.tax = amount;
        if (amount == 0) land.taxStrikes.clear();
        return Result.ok("Daily member tax set to " + amount + ".");
    }

    public Result ban(UUID actor, String name, UUID target, long now) {
        Land land = land(name).orElse(null);
        if (!has(land, actor, Perm.BAN)) return Result.no("You can't ban players here.");
        if (target.equals(land.owner)) return Result.no("The owner can't be banned.");
        if (land.members.containsKey(target)) {
            if (!outranks(land, actor, target)) return Result.no("You must outrank that member.");
            if (guard.blockKick(land.name, target, now)) return Result.no("That member is protected during the election.");
            if (!removeMember(land, target, now)) return Result.no("Estate payout failed; membership retained.");
        }
        land.banned.add(target);
        land.estates.values().forEach(estate -> estate.trusted.remove(target));
        data.invites.remove(key(name) + "|" + target);
        return Result.ok("Player banned.");
    }

    public Result unban(UUID actor, String name, UUID target) {
        Land land = land(name).orElse(null);
        if (!has(land, actor, Perm.BAN)) return Result.no("You can't unban players here.");
        land.banned.remove(target);
        return Result.ok("Player unbanned.");
    }

    public Result adminDelete(String name) {
        return adminDelete(name, System.currentTimeMillis());
    }

    public Result adminDelete(String name, long now) {
        Land land = land(name).orElse(null);
        if (land == null) return Result.no("No such city.");
        Result result = removeLand(land, true, now);
        if (result.ok()) for (Election election : data.openElections(land.name)) election.state = Election.State.VOIDED;
        return result;
    }

    public List<Land> list() {
        return data.lands.values().stream().sorted(Comparator.<Land>comparingInt(l -> l.members.size()).reversed()
                .thenComparing(l -> l.name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public Optional<ChunkPos> unclaimNewest(Land land) {
        if (land.chunks.size() <= 1) return Optional.empty();
        List<String> newest = new ArrayList<>(land.chunks);
        Collections.reverse(newest);
        for (String key : newest) {
            ChunkPos chunk = ChunkPos.fromKey(key);
            if (estateAt(chunk).isPresent()) continue;
            if ((land.spawn != null && land.spawn.chunk().equals(chunk)) || (realms != null && realms.spawnChunk(chunk))) continue;
            land.chunks.remove(key);
            land.plots.remove(key);
            chunks.remove(key);
            return Optional.of(chunk);
        }
        return Optional.empty();
    }

    public List<String> landsOf(UUID player) {
        return data.lands.values().stream().filter(l -> l.members.containsKey(player)).map(l -> l.name)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public Optional<Estate> estate(String name) {
        if (name == null) return Optional.empty();
        return data.lands.values().stream().map(l -> l.estates.get(key(name))).filter(Objects::nonNull).findFirst();
    }
    public Optional<Estate> estateAt(ChunkPos chunk) {
        return landAt(chunk).stream().flatMap(l -> l.estates.values().stream()).filter(e -> e.chunks.contains(chunk.key())).findFirst();
    }
    public Land placeCity(String name) {
        Land city = land(name).orElse(null);
        if (city != null) return city;
        return data.lands.values().stream().filter(l -> l.estates.containsKey(key(name))).findFirst().orElse(null);
    }
    public Spot placeSpawn(String name) {
        Estate estate = estate(name).orElse(null);
        Land city = placeCity(name);
        return estate != null ? estate.spawn : city == null ? null : city.spawn;
    }
    public double placeFee(String name) {
        Estate estate = estate(name).orElse(null);
        Land city = placeCity(name);
        return estate != null ? estate.spawnFee : city == null ? 0 : city.spawnFee;
    }
    public Result createEstate(UUID actor, String name, UUID owner, Spot spot, long now) {
        Land city = landAt(spot.chunk()).orElse(null);
        if (!has(city, actor, Perm.PLOTS)) return Result.no("You need lot-management permission here.");
        if (name == null || !name.matches("[A-Za-z0-9_]{3,24}")) return Result.no("Use 3-24 letters, numbers or underscores.");
        if (land(name).isPresent() || estate(name).isPresent()) return Result.no("That place name is taken.");
        if (!city.members.containsKey(owner)) return Result.no("The estate owner must be a city member.");
        if (city.plots.containsKey(spot.chunk().key()) || estateAt(spot.chunk()).isPresent()) return Result.no("This chunk already has a lot or estate.");
        Estate estate = new Estate();
        estate.name = name; estate.owner = owner; estate.spawn = spot; estate.created = now;
        estate.chunks.add(spot.chunk().key()); estate.flags.put("public", false);
        city.estates.put(key(name), estate); city.plots.put(spot.chunk().key(), owner);
        return Result.ok("Created estate " + name + ".");
    }
    private boolean estateManager(UUID actor, Land city, Estate estate) {
        return estate != null && (estate.owner.equals(actor) || has(city, actor, Perm.PLOTS));
    }
    private boolean estateOwner(UUID actor, Estate estate) { return estate != null && estate.owner.equals(actor); }
    public Result claimEstate(UUID actor, String name, ChunkPos chunk) {
        Estate estate = estate(name).orElse(null); Land city = placeCity(name);
        if (!estateManager(actor, city, estate)) return Result.no("Only the estate owner or city lot managers can claim.");
        if (landAt(chunk).orElse(null) != city) return Result.no("Claim inside the parent city.");
        if (city.plots.containsKey(chunk.key()) || estateAt(chunk).isPresent()) return Result.no("This chunk already has a lot or estate.");
        boolean touches = false;
        for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
            if (estate.chunks.contains(new ChunkPos(chunk.world(), chunk.x()+d[0], chunk.z()+d[1]).key())) touches = true;
        if (!touches) return Result.no("Touch the estate first.");
        estate.chunks.add(chunk.key()); city.plots.put(chunk.key(), estate.owner);
        return Result.ok("Estate chunk added.");
    }
    public Result unclaimEstate(UUID actor, String name, ChunkPos chunk) {
        Estate estate = estate(name).orElse(null); Land city = placeCity(name);
        if (!estateManager(actor, city, estate) || (!estateOwner(actor, estate) && guard.plotsLocked(city.name)))
            return Result.no("Only the estate owner can give up chunks under a charter.");
        if (!estate.chunks.contains(chunk.key())) return Result.no("Stand inside this estate.");
        if (estate.chunks.size() == 1) return Result.no("Keep the last chunk, or delete the estate.");
        if (estate.spawn != null && estate.spawn.chunk().equals(chunk)) return Result.no("Move the estate spawn first.");
        estate.chunks.remove(chunk.key()); city.plots.remove(chunk.key());
        return Result.ok("Estate chunk returned to the city.");
    }
    public Result deleteEstate(UUID actor, String name) {
        return deleteEstate(actor, name, System.currentTimeMillis());
    }

    public Result deleteEstate(UUID actor, String name, long now) {
        Estate estate = estate(name).orElse(null); Land city = placeCity(name);
        if (!estateManager(actor, city, estate) || (!estateOwner(actor, estate) && guard.plotsLocked(city.name)))
            return Result.no("You cannot delete this estate.");
        return dissolve(city, estate, now) ? Result.ok("Estate returned to the city.") : Result.no("Estate payout failed; estate retained.");
    }
    private boolean dissolve(Land city, Estate estate, long now) {
        if (estate.balance > 0) {
            if (economy == null) {
                LOGGER.warning("Dropped estate treasury balance " + estate.balance + " for " + estate.name
                        + " in city " + city.name + ": no economy provider.");
                Bank.record(estate, now, estate.owner, -estate.balance, "Balance dropped: no economy provider");
            } else {
                try { economy.deposit(estate.owner, estate.balance); }
                catch (RuntimeException failure) { return false; }
                Bank.record(estate, now, estate.owner, -estate.balance, "Owner payout");
            }
        }
        estate.chunks.forEach(city.plots::remove); city.estates.remove(key(estate.name));
        return true;
    }
    public Result giveEstate(UUID actor, String name, UUID target) {
        Estate estate = estate(name).orElse(null); Land city = placeCity(name);
        if (!estateOwner(actor, estate)) return Result.no("Only the estate owner can transfer it.");
        if (!city.members.containsKey(target)) return Result.no("The new owner must be a city member.");
        estate.owner = target; estate.chunks.forEach(c -> city.plots.put(c, target));
        return Result.ok("Estate transferred.");
    }
    public Result trustEstate(UUID actor, String name, UUID target, boolean trusted) {
        Estate estate = estate(name).orElse(null);
        if (!estateOwner(actor, estate)) return Result.no("Only the estate owner can change trust.");
        if (trusted) estate.trusted.add(target); else estate.trusted.remove(target);
        return Result.ok(trusted ? "Player trusted." : "Trust removed.");
    }
    public Result publicEstate(UUID actor, String name, boolean enabled) {
        Estate estate = estate(name).orElse(null);
        if (!estateOwner(actor, estate)) return Result.no("Only the estate owner can change flags.");
        estate.flags.put("public", enabled); return Result.ok("Public spawn " + (enabled ? "on." : "off."));
    }
    public Result setEstateSpawn(UUID actor, String name, Spot spot) {
        Estate estate = estate(name).orElse(null);
        if (!estateOwner(actor, estate)) return Result.no("Only the estate owner can set its spawn.");
        if (!estate.chunks.contains(spot.chunk().key())) return Result.no("Stand inside the estate.");
        estate.spawn = spot; return Result.ok("Estate spawn set.");
    }
    public Result fee(UUID actor, String name, double amount, double maximum) {
        Estate estate = estate(name).orElse(null); Land city = placeCity(name);
        if (estate != null ? !estateOwner(actor, estate) : !has(city, actor, Perm.SETTINGS)) return Result.no("You cannot set this fee.");
        if (!Double.isFinite(amount) || amount < 0 || !Double.isFinite(maximum) || amount > maximum) return Result.no("Choose a fee from 0 to " + maximum + ".");
        if (estate != null) estate.spawnFee = amount; else city.spawnFee = amount;
        return Result.ok("Spawn fee set to " + amount + ".");
    }
    /** Run once at the end of warm-up on the server thread. Failed teleports refund the reservation. */
    public Result arrive(UUID actor, String name, long now, java.util.function.BooleanSupplier teleport) {
        Result access = spawn(actor, name); if (!access.ok()) return access;
        Land city = placeCity(name); Estate estate = estate(name).orElse(null);
        boolean friendly = realms != null && realms.ofCity(city.name).map(r -> realms.friendly(actor, r, now)).orElse(false);
        double fee = economy == null || city.members.containsKey(actor) || friendly ? 0 : placeFee(name);
        double balance = estate == null ? city.balance : estate.balance;
        return Arrival.transfer(economy, actor, fee, balance, teleport, amount -> {
            if (estate == null) Bank.record(city, now, actor, amount, "Spawn fee");
            else Bank.record(estate, now, actor, amount, "Spawn fee");
        }, amount -> data.pendingRefunds.merge(actor, amount, Double::sum));
    }
}
