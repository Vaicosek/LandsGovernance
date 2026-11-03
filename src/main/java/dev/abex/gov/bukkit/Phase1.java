package dev.abex.gov.bukkit;

import dev.abex.gov.core.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class Phase1 implements Listener {
    private final GovPlugin plugin;
    private final LandBook book;
    private final ElectionService gov;
    private final NativeLands lands;
    private final EconomyPort economy;
    private final Bank bank;
    private final DayProcessor day;
    private volatile Map<UUID, Map<UUID, NamedTextColor>> chatColors = Map.of();
    private final Map<UUID, String> realmChatting = new ConcurrentHashMap<>();
    private final Set<UUID> chatting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitRunnable> warming = new HashMap<>();
    private final Map<UUID, BukkitRunnable> borders = new HashMap<>();

    Phase1(GovPlugin plugin, LandBook book, ElectionService gov, NativeLands lands, EconomyPort economy) {
        this.plugin = plugin;
        this.book = book;
        this.gov = gov;
        this.lands = lands;
        this.economy = economy;
        book.setEconomy(economy);
        this.bank = new Bank(book, economy);
        day = new DayProcessor(book, economy, plugin.getConfig().getDouble("economy.upkeep-per-chunk", 1),
                plugin.getConfig().getInt("economy.upkeep-grace-days", 3),
                plugin.getConfig().getInt("economy.tax-grace-days", 3), lands::announce);
        day.setRealms(plugin.realms);
    }

    static Spot spot(Location l) {
        return new Spot(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    private void withLand(Player p, Consumer<Land> action) {
        Land here = book.landAt(ProtectListener.chunk(p.getLocation())).orElse(null);
        if (here != null && here.members.containsKey(p.getUniqueId())) { action.accept(here); return; }
        List<String> mine = book.landsOf(p.getUniqueId());
        if (mine.size() == 1) action.accept(book.land(mine.getFirst()).orElseThrow());
        else p.sendMessage(Msg.info(mine.isEmpty() ? "You aren't a member of any city." : "Stand in the city you mean."));
    }

    boolean command(Player p, String sub, String[] args) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (Set.of("deposit", "withdraw", "treasury", "tax", "upkeep").contains(sub) && economy == null) {
            p.sendMessage(Msg.of(Result.no("No economy plugin installed.")));
            return true;
        }
        try {
            switch (sub) {
                case "estate" -> estateCommand(p, args);
                case "fee" -> {
                    if (args.length != 2) { p.sendMessage(Msg.info("Usage: /c fee <amount>")); break; }
                    double amount = Double.parseDouble(args[1]);
                    withLand(p, l -> p.sendMessage(Msg.of(book.fee(id, l.name, amount, plugin.getConfig().getDouble("economy.max-spawn-fee", 100)))));
                }
                case "setspawn" -> withLand(p, l -> p.sendMessage(Msg.of(book.setSpawn(id, l.name, spot(p.getLocation())))));
                case "spawn" -> {
                    if (args.length == 3) {
                        Land city = book.land(args[1]).orElse(null);
                        Estate estate = book.estate(args[2]).orElse(null);
                        if (city == null || estate == null || book.placeCity(args[2]) != city) p.sendMessage(Msg.info("No such estate in that city."));
                        else teleport(p, estate.name);
                    } else if (args.length == 2) teleport(p, args[1]);
                    else withLand(p, l -> teleport(p, l.name));
                }
                case "map" -> map(p);
                case "claimat" -> {
                    if (args.length != 3) { p.sendMessage(Msg.info("Click a wilderness cell in /c map.")); break; }
                    ChunkPos from = ProtectListener.chunk(p.getLocation());
                    p.sendMessage(Msg.of(book.claimAt(id, from, new ChunkPos(from.world(), Integer.parseInt(args[1]), Integer.parseInt(args[2])))));
                    map(p);
                }
                case "flag" -> withLand(p, l -> {
                    if (args.length == 1) flags(p, l);
                    else if (args.length == 3 && Set.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT))) {
                        p.sendMessage(Msg.of(book.flag(id, l.name, args[1].toLowerCase(Locale.ROOT), args[2].equalsIgnoreCase("on"))));
                        flags(p, l);
                    } else p.sendMessage(Msg.info("Usage: /c flag <name> <on|off>"));
                });
                case "deposit", "withdraw", "tax" -> {
                    if (args.length != 2) { p.sendMessage(Msg.info("Usage: /c " + sub + " <amount>")); break; }
                    double amount = Double.parseDouble(args[1]);
                    withLand(p, l -> {
                        Result result = switch (sub) {
                            case "deposit" -> bank.deposit(id, l.name, amount, now);
                            case "withdraw" -> bank.withdraw(id, l.name, amount, now);
                            default -> book.tax(id, l.name, amount, plugin.getConfig().getDouble("economy.max-tax", 100));
                        };
                        p.sendMessage(Msg.of(result));
                        if (result.ok() && sub.equals("tax")) lands.announce(l.name, "Daily member tax is now " + amount + ".");
                    });
                }
                case "treasury" -> withLand(p, l -> {
                    p.sendMessage(Msg.info(l.name + " treasury: " + l.balance));
                    for (LedgerEntry entry : bank.recent(l)) p.sendMessage(Msg.info(Instant.ofEpochMilli(entry.time()) + " "
                            + (entry.who() == null ? "City" : lands.name(entry.who())) + " " + entry.amount() + " " + entry.reason()));
                });
                case "upkeep" -> withLand(p, l -> p.sendMessage(Msg.info("Daily upkeep: "
                        + l.chunks.size() * plugin.getConfig().getDouble("economy.upkeep-per-chunk", 1)
                        + "; unpaid days: " + l.upkeepStrikes + ".")));
                case "ban", "unban" -> {
                    if (args.length < 2 || args.length > 3) { p.sendMessage(Msg.info("Usage: /c " + sub + " <player>")); break; }
                    OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                    if (target == null) { p.sendMessage(Msg.info("No known player called " + args[1] + ".")); break; }
                    Consumer<Land> action = l -> p.sendMessage(Msg.of(sub.equals("ban") ? book.ban(id, l.name, target.getUniqueId(), now)
                            : book.unban(id, l.name, target.getUniqueId())));
                    if (args.length == 3) book.land(args[2]).ifPresentOrElse(action, () -> p.sendMessage(Msg.info("No such city.")));
                    else withLand(p, action);
                }
                case "chat" -> {
                    realmChatting.remove(id);
                    if (args.length > 1) chat(p, Component.text(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
                    else if (chatting.remove(id)) p.sendMessage(Msg.info("City chat off."));
                    else withLand(p, l -> { chatting.add(id); p.sendMessage(Msg.info("City chat on.")); });
                }
                case "show" -> show(p);
                case "list" -> list(p, args.length > 1 ? Integer.parseInt(args[1]) : 1);
                case "info" -> {
                    if (args.length > 1) book.land(args[1]).ifPresentOrElse(l -> info(p, l), () -> p.sendMessage(Msg.info("No such city.")));
                    else withLand(p, l -> info(p, l));
                }
                default -> { return false; }
            }
        } catch (NumberFormatException e) { p.sendMessage(Msg.of(Result.no("Use a valid number."))); }
        return true;
    }

    private void flags(Player p, Land land) {
        land.flags.forEach((name, enabled) -> p.sendMessage(Msg.info(name + ": " + (enabled ? "on " : "off "))
                .append(Msg.button(enabled ? "off" : "on", "/c flag " + name + (enabled ? " off" : " on"), "Toggle " + name))));
    }

    private void map(Player p) {
        p.sendMessage(Msg.info("North ↑ | yellow: you, aqua: your realm, green: allies, red: enemies, gray: others"));
        for (var row : ClaimMap.render(book, ProtectListener.chunk(p.getLocation()), p.getUniqueId(), 4)) {
            Component line = Component.empty();
            for (var cell : row) {
                NamedTextColor color = switch (cell.kind()) {
                    case YOU -> NamedTextColor.YELLOW;
                    case OWN_LAND -> NamedTextColor.GREEN;
                    case OWN_PLOT -> NamedTextColor.AQUA;
                    case OTHER_LAND -> NamedTextColor.GRAY;
                    case WILD -> NamedTextColor.DARK_GRAY;
                };
                Land city = book.landAt(cell.chunk()).orElse(null);
                Realm realm = city == null ? null : plugin.realms.ofCity(city.name).orElse(null);
                if (realm != null && cell.kind() != ClaimMap.Kind.YOU) {
                    if (plugin.realms.enemy(p.getUniqueId(), realm, System.currentTimeMillis())) color = NamedTextColor.RED;
                    else if (plugin.realms.ofPlayer(p.getUniqueId()).contains(realm)) color = NamedTextColor.AQUA;
                    else if (plugin.realms.friendly(p.getUniqueId(), realm, System.currentTimeMillis())) color = NamedTextColor.GREEN;
                }
                Component square = Component.text("■", color).hoverEvent(Component.text(cell.land() + (realm == null ? "" : " (" + realm.name + ")") + (cell.claimable() ? " — click to claim" : "")));
                if (cell.claimable() && cell.kind() == ClaimMap.Kind.WILD)
                    square = square.clickEvent(ClickEvent.runCommand("/c claimat " + cell.chunk().x() + " " + cell.chunk().z()));
                line = line.append(square);
            }
            p.sendMessage(line);
        }
    }

    private void teleport(Player p, String name) { teleport(p, name, false); }
    void realmTeleport(Player p, String name) { teleport(p, name, true); }
    private Result access(Player p, String name, boolean realm) {
        return realm ? plugin.realms.spawn(p.getUniqueId(), plugin.realms.realm(name).orElse(null), System.currentTimeMillis()) : book.spawn(p.getUniqueId(), name);
    }
    private void teleport(Player p, String name, boolean realm) {
        Result result = access(p, name, realm);
        if (!result.ok()) { p.sendMessage(Msg.of(result)); return; }
        cancelWarmup(p, false);
        int seconds = Math.max(0, plugin.getConfig().getInt("spawn.warmup-seconds", 3));
        if (seconds == 0) { finishTeleport(p, name, realm); return; }
        BukkitRunnable task = new BukkitRunnable() {
            public void run() { warming.remove(p.getUniqueId()); if (p.isOnline()) finishTeleport(p, name, realm); }
        };
        warming.put(p.getUniqueId(), task);
        task.runTaskLater(plugin, seconds * 20L);
        p.sendMessage(Msg.info("Stand still for " + seconds + " seconds."));
    }

    private void finishTeleport(Player p, String name, boolean realm) {
        Result result = access(p, name, realm);
        if (!result.ok()) { p.sendMessage(Msg.of(result)); return; }
        Spot spot = realm ? plugin.realms.realm(name).orElseThrow().spawn : book.placeSpawn(name);
        World world = Bukkit.getWorld(spot.world());
        if (world == null) { p.sendMessage(Msg.info("The spawn world isn't loaded.")); return; }
        java.util.function.BooleanSupplier teleport = () -> p.teleport(new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch()));
        p.sendMessage(Msg.of(realm ? plugin.realms.arrive(p.getUniqueId(), plugin.realms.realm(name).orElseThrow(), System.currentTimeMillis(), teleport)
                : book.arrive(p.getUniqueId(), name, System.currentTimeMillis(), teleport)));
        plugin.save();
    }

    private void cancelWarmup(Player p, boolean tell) {
        BukkitRunnable task = warming.remove(p.getUniqueId());
        if (task == null) return;
        task.cancel();
        if (tell) p.sendMessage(Msg.info("Spawn cancelled because you moved."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) { checkMove(event); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void teleportEvent(PlayerTeleportEvent event) { checkMove(event); }

    private void checkMove(PlayerMoveEvent event) {
        Location to = event.getTo(), from = event.getFrom();
        if (to == null) return;
        boolean moved = !Objects.equals(from.getWorld(), to.getWorld()) || from.getX() != to.getX()
                || from.getY() != to.getY() || from.getZ() != to.getZ();
        if (moved) cancelWarmup(event.getPlayer(), true);
        if (ProtectListener.chunk(from).equals(ProtectListener.chunk(to))) return;
        Land land = book.landAt(ProtectListener.chunk(to)).orElse(null);
        if (land != null && land.banned.contains(event.getPlayer().getUniqueId())
                && !plugin.bypass.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("You are banned from " + land.name + "."));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void asyncChat(AsyncChatEvent event) {
        if (!chatting.contains(event.getPlayer().getUniqueId()) && !realmChatting.containsKey(event.getPlayer().getUniqueId())) {
            var original = event.renderer();
            var colors = chatColors.getOrDefault(event.getPlayer().getUniqueId(), Map.of());
            event.renderer((source, displayName, message, viewer) -> {
                NamedTextColor color = viewer instanceof Player target ? colors.get(target.getUniqueId()) : null;
                return original.render(source, color == null ? displayName : displayName.color(color), message, viewer);
            });
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Component message = event.message();
        // Claim data and online membership are confined to the server thread.
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) {
            String realm = realmChatting.get(player.getUniqueId());
            if (realm != null) plugin.realms.realm(realm).ifPresent(r -> realmChat(player, r, message));
            else chat(player, message);
        } });
    }

    /** Publish immutable colour data on the main thread; async renderers never read realm state. */
    void refreshChatColors() {
        Map<UUID, Map<UUID, NamedTextColor>> snapshot = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Player source : Bukkit.getOnlinePlayers()) {
            Map<UUID, NamedTextColor> viewers = new HashMap<>();
            List<Realm> sourceRealms = plugin.realms.ofPlayer(source.getUniqueId());
            for (Player target : Bukkit.getOnlinePlayers()) {
                List<Realm> targetRealms = plugin.realms.ofPlayer(target.getUniqueId());
                boolean enemy = sourceRealms.stream().anyMatch(a -> targetRealms.stream().anyMatch(b -> plugin.realms.relation(a, b, now).equals("enemy")));
                boolean ally = sourceRealms.stream().anyMatch(a -> targetRealms.stream().anyMatch(b -> plugin.realms.relation(a, b, now).equals("ally")));
                if (enemy || ally) viewers.put(target.getUniqueId(), enemy ? NamedTextColor.RED : NamedTextColor.GREEN);
            }
            snapshot.put(source.getUniqueId(), Map.copyOf(viewers));
        }
        chatColors = Map.copyOf(snapshot);
    }

    void toggleRealmChat(Player p, Realm r) {
        chatting.remove(p.getUniqueId());
        if (realmChatting.remove(p.getUniqueId()) != null) p.sendMessage(Msg.info("Realm chat off."));
        else { realmChatting.put(p.getUniqueId(), r.name); p.sendMessage(Msg.info("Realm chat on.")); }
    }

    void realmChat(Player p, Realm r, Component message) {
        if (!plugin.realms.members(r).contains(p.getUniqueId())) { realmChatting.remove(p.getUniqueId()); p.sendMessage(Msg.info("You are no longer in that realm.")); return; }
        Component line = Component.text("[" + r.name + "] " + p.getName() + ": ", NamedTextColor.AQUA).append(message);
        for (UUID member : plugin.realms.members(r)) { Player target = Bukkit.getPlayer(member); if (target != null) target.sendMessage(line); }
    }

    private void chat(Player p, Component message) {
        withLand(p, l -> {
            Component line = Component.text("[" + l.name + "] " + p.getName() + ": ", NamedTextColor.GREEN).append(message);
            for (UUID member : l.members.keySet()) {
                Player target = Bukkit.getPlayer(member);
                if (target != null) target.sendMessage(line);
            }
        });
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        chatting.remove(id);
        realmChatting.remove(id);
        plugin.bypass.remove(id);
        cancelWarmup(event.getPlayer(), false);
        BukkitRunnable task = borders.remove(id);
        if (task != null) task.cancel();
    }

    private void show(Player p) {
        UUID id = p.getUniqueId();
        BukkitRunnable previous = borders.remove(id);
        if (previous != null) previous.cancel();
        BukkitRunnable task = new BukkitRunnable() {
            int seconds;
            public void run() {
                if (!p.isOnline() || seconds++ >= 10) { borders.remove(id); cancel(); return; }
                ChunkPos center = ProtectListener.chunk(p.getLocation());
                double y = p.getEyeLocation().getY();
                for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                    ChunkPos chunk = new ChunkPos(center.world(), center.x() + dx, center.z() + dz);
                    Land land = book.landAt(chunk).orElse(null);
                    if (land == null) continue;
                    Particle.DustOptions dust = new Particle.DustOptions(land.members.containsKey(id) ? Color.LIME : Color.RED, 1);
                    double x = chunk.x() * 16.0, z = chunk.z() * 16.0;
                    for (int i = 0; i < 16; i += 2) for (int h : new int[]{-1, 1}) {
                        particle(p, x + i, y + h, z, dust);
                        particle(p, x + i, y + h, z + 16, dust);
                        particle(p, x, y + h, z + i, dust);
                        particle(p, x + 16, y + h, z + i, dust);
                    }
                }
            }
        };
        borders.put(id, task);
        task.runTaskTimer(plugin, 0, 20);
    }

    private void particle(Player p, double x, double y, double z, Particle.DustOptions dust) {
        p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
    }

    private void list(Player p, int page) {
        List<Land> all = book.list();
        int pages = Math.max(1, (all.size() + 9) / 10);
        page = Math.max(1, Math.min(page, pages));
        p.sendMessage(Msg.info("(page " + page + "/" + pages + ")"));
        for (int i = (page - 1) * 10; i < Math.min(page * 10, all.size()); i++) {
            Land land = all.get(i);
            p.sendMessage(Msg.button(land.name, "/c info " + land.name, "View city")
                    .append(Component.text(" " + land.members.size() + (land.members.size() == 1 ? " member" : " members"))));
        }
        if (page > 1) p.sendMessage(Msg.button("Previous", "/c list " + (page - 1), "Previous page"));
        if (page < pages) p.sendMessage(Msg.button("Next", "/c list " + (page + 1), "Next page"));
    }

    private void info(Player p, Land land) {
        p.sendMessage(Msg.info(land.name + " — owner: " + lands.name(land.owner) + "; members: " + land.members.size()
                + "; chunks: " + land.chunks.size() + "; charter: " + gov.charter(land.name).mode.name().toLowerCase(Locale.ROOT) + "."));
        plugin.realmCommands.cityLink(p, land, false);
        for (Election e : gov.data().openElections(land.name)) p.sendMessage(Msg.info("Open election: " + e.role));
        p.sendMessage(Msg.info("Visitor flags: " + land.flags));
        for (Estate estate : land.estates.values()) p.sendMessage(Msg.info(estate.name + " — owner: " + lands.name(estate.owner) + " ")
                .append(Msg.button("Spawn (" + estate.spawnFee + ")", "/c spawn " + estate.name, "Visit " + estate.name)));
        if (land.members.containsKey(p.getUniqueId()) || land.flags.getOrDefault("public", false)) p.sendMessage(Msg.button("Spawn (" + land.spawnFee + ")", "/c spawn " + land.name, "Visit " + land.name));
    }

    void status(Player p) {
        book.estateAt(ProtectListener.chunk(p.getLocation())).ifPresent(e -> estateStatus(p, e));
        for (String name : book.landsOf(p.getUniqueId())) {
            Land land = book.land(name).orElseThrow();
            plugin.realmCommands.cityLink(p, land);
            for (UUID banned : land.banned) p.sendMessage(Msg.info(name + " banned: " + lands.name(banned) + " ")
                    .append(Msg.button("unban", "/c unban " + lands.name(banned) + " " + name, "Unban from " + name)));
            if (p.getUniqueId().equals(land.owner)) land.taxStrikes.forEach((who, count) -> {
                if (count >= plugin.getConfig().getInt("economy.tax-grace-days", 3))
                    p.sendMessage(Msg.info(name + ": " + lands.name(who) + " has " + count + " unpaid tax days."));
            });
            Integer ownStrikes = land.taxStrikes.get(p.getUniqueId());
            if (ownStrikes != null) p.sendMessage(Msg.info(name + ": you have " + ownStrikes + " unpaid tax days."));
        }
        p.sendMessage(Msg.button("Map", "/c map", "Click to claim").append(Component.space())
                .append(Msg.button("Cities", "/c list", "Browse cities")).append(Component.space())
                .append(Msg.button("Flags", "/c flag", "City settings")).append(Component.space())
                .append(Msg.button("Spawn", "/c spawn", "Go home")));
    }

    void tickDay() {
        if (economy == null) return;
        ZonedDateTime now = ZonedDateTime.now();
        int hour = Math.max(0, Math.min(23, plugin.getConfig().getInt("economy.day-hour", 0)));
        LocalDate date = now.toLocalDate();
        if (now.getHour() < hour) date = date.minusDays(1);
        String key = date.toString();
        // Start a new installation without charging for a day before it was enabled.
        if (gov.data().lastEconomyDay == null) { gov.data().lastEconomyDay = key; return; }
        if (key.compareTo(gov.data().lastEconomyDay) <= 0) return;
        day.runDay(System.currentTimeMillis());
        gov.data().lastEconomyDay = key;
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (String name : book.landsOf(p.getUniqueId())) {
                Land land = book.land(name).orElseThrow();
                int strikes = land.taxStrikes.getOrDefault(p.getUniqueId(), 0);
                if (strikes >= plugin.getConfig().getInt("economy.tax-grace-days", 3))
                    p.sendMessage(Msg.info(name + ": you have " + strikes + " unpaid tax days. You remain a member."));
            }
        }
    }

    private void estateStatus(Player p, Estate estate) {
        p.sendMessage(Msg.info(estate.name + " — owner: " + lands.name(estate.owner) + "; fee: " + estate.spawnFee
                + (estate.owner.equals(p.getUniqueId()) ? "; treasury: " + estate.balance : ""))
                .append(Component.space()).append(Msg.button("Spawn", "/c spawn " + estate.name, "Visit estate")));
        if (estate.owner.equals(p.getUniqueId())) p.sendMessage(Msg.button("Treasury", "/c estate treasury", "View ledger")
                .append(Component.space()).append(Msg.button("Public " + (estate.flags.getOrDefault("public", false) ? "off" : "on"),
                        "/c estate flag public " + (estate.flags.getOrDefault("public", false) ? "off" : "on"), "Toggle public spawn")));
    }
    private void estateCommand(Player p, String[] args) {
        UUID actor = p.getUniqueId(); long now = System.currentTimeMillis();
        ChunkPos chunk = ProtectListener.chunk(p.getLocation());
        if (args.length < 2) {
            book.estateAt(chunk).ifPresentOrElse(e -> estateStatus(p, e), () -> p.sendMessage(Msg.info("/c estate create <name> <owner>; claim, unclaim, delete, give, trust, untrust, setspawn, flag, fee, treasury, deposit, withdraw")));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("create")) {
            if (args.length != 4) { p.sendMessage(Msg.info("Usage: /c estate create <name> <owner>")); return; }
            OfflinePlayer owner = Bukkit.getOfflinePlayerIfCached(args[3]);
            if (owner == null) { p.sendMessage(Msg.info("No known player called " + args[3])); return; }
            p.sendMessage(Msg.of(book.createEstate(actor, args[2], owner.getUniqueId(), spot(p.getLocation()), now))); return;
        }
        Estate estate = action.equals("delete") && args.length == 3 ? book.estate(args[2]).orElse(null) : book.estateAt(chunk).orElse(null);
        if (action.equals("claim")) {
            List<Estate> adjacent = new ArrayList<>();
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) book.estateAt(new ChunkPos(chunk.world(), chunk.x()+d[0], chunk.z()+d[1]))
                    .filter(e -> e.owner.equals(actor) || book.has(book.placeCity(e.name), actor, Perm.PLOTS))
                    .ifPresent(e -> { if (!adjacent.contains(e)) adjacent.add(e); });
            if (args.length == 3) estate = adjacent.stream().filter(e -> e.name.equalsIgnoreCase(args[2])).findFirst().orElse(null);
            else if (adjacent.size() == 1) estate = adjacent.getFirst();
            else {
                p.sendMessage(Msg.info("Choose a touching estate:"));
                for (Estate e : adjacent) p.sendMessage(Msg.button(e.name, "/c estate claim " + e.name, "Add this chunk"));
                return;
            }
        }
        if (estate == null) { p.sendMessage(Msg.info("Stand in an estate, or beside it to claim.")); return; }
        Result result;
        switch (action) {
            case "claim" -> result = book.claimEstate(actor, estate.name, chunk);
            case "unclaim" -> result = book.unclaimEstate(actor, estate.name, chunk);
            case "delete" -> result = book.deleteEstate(actor, estate.name);
            case "setspawn" -> result = book.setEstateSpawn(actor, estate.name, spot(p.getLocation()));
            case "give", "trust", "untrust" -> {
                if (args.length != 3) { p.sendMessage(Msg.info("Usage: /c estate " + action + " <player>")); return; }
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[2]);
                if (target == null) { p.sendMessage(Msg.info("No known player called " + args[2])); return; }
                result = action.equals("give") ? book.giveEstate(actor, estate.name, target.getUniqueId())
                        : book.trustEstate(actor, estate.name, target.getUniqueId(), action.equals("trust"));
            }
            case "flag" -> {
                if (args.length != 4 || !args[2].equalsIgnoreCase("public") || !Set.of("on", "off").contains(args[3].toLowerCase(Locale.ROOT))) {
                    p.sendMessage(Msg.info("Usage: /c estate flag public <on|off>")); return;
                }
                result = book.publicEstate(actor, estate.name, args[3].equalsIgnoreCase("on"));
            }
            case "fee", "deposit", "withdraw" -> {
                if (args.length != 3) { p.sendMessage(Msg.info("Usage: /c estate " + action + " <amount>")); return; }
                double amount = Double.parseDouble(args[2]);
                result = switch (action) {
                    case "fee" -> book.fee(actor, estate.name, amount, plugin.getConfig().getDouble("economy.max-spawn-fee", 100));
                    case "deposit" -> bank.estateDeposit(actor, estate.name, amount, now);
                    default -> bank.estateWithdraw(actor, estate.name, amount, now);
                };
            }
            case "treasury" -> {
                if (!estate.owner.equals(actor)) { p.sendMessage(Msg.info("Only the estate owner can view its treasury.")); return; }
                estateStatus(p, estate);
                for (int i = estate.ledger.size()-1; i >= Math.max(0, estate.ledger.size()-10); i--) {
                    LedgerEntry entry = estate.ledger.get(i);
                    p.sendMessage(Msg.info(Instant.ofEpochMilli(entry.time()) + " " + (entry.who() == null ? "Estate" : lands.name(entry.who())) + " " + entry.amount() + " " + entry.reason()));
                }
                return;
            }
            default -> { p.sendMessage(Msg.info("Unknown estate command. Use /c estate.")); return; }
        }
        p.sendMessage(Msg.of(result));
    }
}
