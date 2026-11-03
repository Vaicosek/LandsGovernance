package dev.abex.gov.bukkit;

import dev.abex.gov.core.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;

/** /c and its subcommands. Claims, membership and governance. */
final class GovCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("realm", "rc", "estate", "shop", "fee", "create", "claim", "unclaim", "invite", "join", "kick", "role", "lot", "leave", "delete", "owner", "vet", "elect", "run", "vote", "cancel", "charter", "admin", "spawn", "setspawn", "map", "flag", "deposit", "withdraw", "treasury", "tax", "upkeep", "ban", "unban", "chat", "show", "list", "info");

    private final GovPlugin plugin;
    private final ElectionService gov;
    private final NativeLands lands;
    private final LandBook book;

    GovCommand(GovPlugin plugin, ElectionService gov, NativeLands lands, LandBook book) {
        this.plugin = plugin;
        this.gov = gov;
        this.lands = lands;
        this.book = book;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            admin(sender, args);
            plugin.save();
            return true;
        }
        // "/execute as <player> run city ..." and command blocks arrive wrapped
        if (sender instanceof ProxiedCommandSender proxied) sender = proxied.getCallee();
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only (staff: /c admin).");
            return true;
        }
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();
        if (plugin.realmCommands.command(p, sub, args) || plugin.shopCommands.command(p, sub, args) || plugin.features.command(p, sub, args)) {
            plugin.observeAll(now);
            plugin.save();
            return true;
        }
        switch (sub) {
            case "create" -> p.sendMessage(Msg.of(args.length < 2 ? Result.no("Usage: /c create <name>")
                    : book.create(id, args[1], Phase1.spot(p.getLocation()), now)));
            case "claim" -> p.sendMessage(Msg.of(book.claim(id, chunk(p))));
            case "unclaim" -> p.sendMessage(Msg.of(book.unclaim(id, chunk(p))));
            case "join" -> p.sendMessage(Msg.of(args.length < 2 ? Result.no("Usage: /c join <city>")
                    : book.join(id, args[1], now)));
            case "invite", "kick", "owner", "role" -> {
                if (args.length < 2 || (sub.equals("role") && args.length < 3)) {
                    p.sendMessage(Msg.of(Result.no("Usage: /c " + sub + " <player>" + (sub.equals("role") ? " <role>" : ""))));
                    break;
                }
                withTarget(p, args[1], target -> withLand(p, land -> {
                    Result result = switch (sub) {
                        case "invite" -> book.invite(id, land, target, now);
                        case "kick" -> book.kick(id, land, target, now);
                        case "owner" -> book.transfer(id, land, target, now);
                        default -> book.setRole(id, land, target, args[2]);
                    };
                    p.sendMessage(Msg.of(result));
                    if (result.ok() && sub.equals("invite")) {
                        Player invited = Bukkit.getPlayer(target);
                        if (invited != null) invited.sendMessage(Msg.info("Invited to " + land + ". ")
                                .append(Msg.button("Join", "/c join " + land, "Accept invitation")));
                    }
                }));
            }
            case "lot" -> {
                if (args.length == 2 && args[1].equalsIgnoreCase("revoke")) {
                    p.sendMessage(Msg.of(book.revokePlot(id, chunk(p))));
                } else if (args.length == 3 && args[1].equalsIgnoreCase("give")) {
                    withTarget(p, args[2], target -> p.sendMessage(Msg.of(book.givePlot(id, chunk(p), target))));
                } else p.sendMessage(Msg.of(Result.no("Usage: /c lot give <player> or /c lot revoke")));
            }
            case "leave" -> withLand(p, land -> p.sendMessage(Msg.of(book.leave(id, land))));
            case "delete" -> withLand(p, land -> p.sendMessage(Msg.of(book.delete(id, land, now))));
            case "" -> status(p, now);
            case "vet" -> {
                long minutes = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60;
                String ip = p.getAddress() == null ? null : p.getAddress().getAddress().getHostAddress();
                p.sendMessage(Msg.of(gov.tryVet(id, minutes, ip == null ? null : plugin.ipHash(ip))));
            }
            case "elect" -> {
                if (args.length < 2) {
                    p.sendMessage(Msg.info("Usage: /c elect <role> [hours]"));
                    break;
                }
                withLand(p, land -> {
                    int hours = args.length > 2 ? parseInt(args[2]) : 0;
                    p.sendMessage(Msg.of(gov.startElection(land, id, args[1], hours, now)));
                });
            }
            case "run" -> withLand(p, land -> p.sendMessage(Msg.of(gov.run(land, args.length > 1 ? args[1] : null, id, now))));
            case "vote" -> withLand(p, land -> {
                if (args.length < 2) {
                    ballot(p, land);
                    return;
                }
                Optional<UUID> cand = candidateByName(land, args[1]);
                p.sendMessage(cand.isEmpty()
                        ? Msg.of(Result.no(args[1] + " isn't standing in " + land + "."))
                        : Msg.of(gov.vote(land, id, cand.get(), now)));
            });
            case "cancel" -> withLand(p, land -> p.sendMessage(Msg.of(gov.cancel(land, id))));
            case "charter" -> withLand(p, land -> charter(p, land, args, now));
            default -> p.sendMessage(Msg.info("Unknown. Try /c"));
        }
        plugin.observeAll(now);
        plugin.save();
        return true;
    }

    private void status(Player p, long now) {
        plugin.features.status(p);
        UUID id = p.getUniqueId();
        Land here = book.landAt(chunk(p)).orElse(null);
        // Cities the player belongs to already got their realm line from features.status above.
        if (here != null && !here.members.containsKey(id)) plugin.realmCommands.cityLink(p, here);
        if (here == null) p.sendMessage(Msg.info("Wilderness."));
        else {
            UUID plot = here.plots.get(chunk(p).key());
            p.sendMessage(Msg.info(here.name + ": owner " + lands.name(here.owner) + ", your role: "
                    + here.members.getOrDefault(id, "visitor") + (plot == null ? "" : ", lot owner: " + lands.name(plot)) + "."));
        }
        p.sendMessage(gov.isVetted(id)
                ? Msg.info("You are vetted.")
                : Msg.info("You are not vetted, so you can't vote or run. ")
                .append(Msg.button("Get vetted", "/c vet", "Needs " + gov.settings().minPlaytimeHours() + " h playtime")));
        List<String> mine = book.landsOf(id);
        if (mine.isEmpty()) {
            p.sendMessage(Msg.info("You aren't a member of any city."));
            return;
        }
        for (String land : mine) {
            long days = gov.tenureDays(land, id, now);
            String role = lands.roleOf(land, id).orElse("-");
            Charter c = gov.charter(land);
            p.sendMessage(Msg.info(land + ": " + role + (days >= 0 ? ", " + days + " days" : "")
                    + ". Charter: " + c.mode.name().toLowerCase(Locale.ROOT)
                    + (c.revokeAt > 0 ? " (ending)" : "") + "."));
            for (Election e : gov.data().openElections(land)) {
                long h = Math.max(0, (e.closes - now) / 3_600_000L);
                p.sendMessage(Component.text("  Election for " + e.role + ", closes in " + h + " h, "
                                + e.candidates.size() + " standing. ", NamedTextColor.GRAY)
                        .append(Msg.button("Vote", "/c vote", "See the candidates"))
                        .append(Component.space())
                        .append(Msg.button("Stand", "/c run " + e.role, "Stand for " + e.role)));
            }
        }
    }

    private void ballot(Player p, String land) {
        List<Election> open = gov.data().openElections(land);
        if (open.isEmpty()) {
            p.sendMessage(Msg.info("No open election in " + land + "."));
            return;
        }
        for (Election e : open) {
            Map<UUID, Integer> tally = e.tally();
            UUID mine = e.votes.get(p.getUniqueId());
            p.sendMessage(Msg.info("Election for " + e.role + ":"));
            if (e.candidates.isEmpty()) p.sendMessage(Component.text("  Nobody is standing yet.", NamedTextColor.GRAY));
            for (UUID c : e.candidates) {
                String name = lands.name(c);
                p.sendMessage(Component.text("  " + name + " (" + tally.get(c) + ") ", NamedTextColor.WHITE)
                        .append(c.equals(mine)
                                ? Component.text("your vote", NamedTextColor.GREEN)
                                : Msg.button("Vote", "/c vote " + name, "Vote for " + name + " as " + e.role)));
            }
        }
    }

    private void charter(Player p, String land, String[] args, long now) {
        UUID id = p.getUniqueId();
        if (args.length >= 3 && args[1].equalsIgnoreCase("sign")) {
            Charter.Mode m = switch (args[2].toLowerCase(Locale.ROOT)) {
                case "council" -> Charter.Mode.COUNCIL;
                case "republic" -> Charter.Mode.REPUBLIC;
                default -> null;
            };
            p.sendMessage(m == null ? Msg.info("Charters: council or republic.") : Msg.of(gov.signCharter(land, id, m, now)));
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("revoke")) {
            p.sendMessage(Msg.of(gov.revokeCharter(land, id, now)));
        } else {
            Charter c = gov.charter(land);
            p.sendMessage(Msg.info(land + " charter: " + c.mode.name().toLowerCase(Locale.ROOT) + ". ")
                    .append(Msg.button("Council", "/c charter sign council", "Owner can't cancel elections; offices are re-elected every "
                            + gov.settings().termDays() + " days"))
                    .append(Component.space())
                    .append(Msg.button("Republic", "/c charter sign republic", "Like council, and the owner is elected too")));
        }
    }

    private void admin(CommandSender s, String[] args) {
        if (!s.hasPermission("cities.admin")) {
            s.sendMessage(Msg.info("No permission."));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("bypass")) {
            if (!(s instanceof Player player)) { s.sendMessage(Msg.info("Players only.")); return; }
            UUID id = player.getUniqueId();
            boolean enabled = plugin.bypass.add(id);
            if (!enabled) plugin.bypass.remove(id);
            s.sendMessage(Msg.info("Protection bypass " + (enabled ? "on." : "off.")));
            return;
        }
        if (args.length < 3) {
            s.sendMessage(Msg.info("/c admin vet|unvet <player>, close|delete <city>, bypass"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "vet", "unvet" -> {
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[2]);
                if (target == null) {
                    s.sendMessage(Msg.info("Never seen a player called " + args[2] + "."));
                    return;
                }
                boolean vet = args[1].equalsIgnoreCase("vet");
                gov.setVetted(target.getUniqueId(), vet, s.getName());
                s.sendMessage(Msg.info(args[2] + (vet ? " is now vetted." : " is no longer vetted.")));
            }
            case "delete" -> {
                Result result = book.adminDelete(args[2]);
                s.sendMessage(Msg.of(result));
                if (result.ok()) plugin.getLogger().warning(s.getName() + " deleted city " + args[2] + "; open elections voided.");
            }
            case "close", "end" -> s.sendMessage(Msg.of(gov.closeNow(args[2], System.currentTimeMillis())));
            // staff support and testing: run /c as an online player; their replies are copied back to you
            case "as" -> {
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null || args.length < 4) {
                    s.sendMessage(Msg.info("/c admin as <online player> <city command...>"));
                    return;
                }
                Msg.echo = s;
                try {
                    target.performCommand("c " + String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                } finally {
                    Msg.echo = null;
                }
            }
            default -> s.sendMessage(Msg.info("/c admin vet|unvet <player>, /c admin close <city>"));
        }
    }

    /** The land you stand in if you're a member there, else your only land. */
    private void withLand(Player p, java.util.function.Consumer<String> action) {
        UUID id = p.getUniqueId();
        Land here = book.landAt(chunk(p)).orElse(null);
        if (here != null && here.members.containsKey(id)) {
            action.accept(here.name);
            return;
        }
        List<String> mine = book.landsOf(id);
        if (mine.size() == 1) action.accept(mine.getFirst());
        else if (mine.isEmpty()) p.sendMessage(Msg.info("You aren't a member of any city."));
        else p.sendMessage(Msg.info("You're in several cities (" + String.join(", ", mine) + "). Stand inside the one you mean."));
    }

    private Optional<UUID> candidateByName(String land, String name) {
        return gov.data().openElections(land).stream()
                .flatMap(e -> e.candidates.stream())
                .filter(c -> lands.name(c).equalsIgnoreCase(name))
                .findFirst();
    }

    private static ChunkPos chunk(Player player) {
        return ProtectListener.chunk(player.getLocation());
    }

    private void withTarget(Player player, String name, java.util.function.Consumer<UUID> action) {
        OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) player.sendMessage(Msg.of(Result.no("No known player called " + name + ".")));
        else action.accept(target.getUniqueId());
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> matching(List<String> choices, String prefix) {
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("realm") && sender instanceof Player p) return plugin.realmCommands.complete(p, args);
        if (sub.equals("shop")) {
            if (args.length == 2) return matching(List.of("create", "remove", "buy", "sell", "log", "web"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("log")) {
                List<String> choices = new ArrayList<>(List.of("1"));
                if (sender.hasPermission("cities.admin")) Bukkit.getOnlinePlayers().forEach(p -> choices.add(p.getName()));
                return matching(choices, args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("log") && sender.hasPermission("cities.admin")) return matching(List.of("1"), args[3]);
        }
        if ((args.length == 2 && Set.of("elect", "run").contains(sub)) || (args.length == 3 && sub.equals("role"))) {
            return matching(book.roles().names(), args[args.length - 1]);
        }
        if (args.length == 2 && Set.of("join", "spawn", "info").contains(sub)) {
            List<String> places = new ArrayList<>(book.list().stream().map(l -> l.name).toList());
            if (sub.equals("spawn")) book.list().forEach(l -> l.estates.values().forEach(e -> places.add(e.name)));
            return matching(places, args[1]);
        }
        if (args.length == 3 && sub.equals("spawn")) return matching(book.land(args[1]).stream()
                .flatMap(l -> l.estates.values().stream()).map(e -> e.name).toList(), args[2]);
        if (sub.equals("estate")) {
            if (args.length == 2) return matching(List.of("create", "claim", "unclaim", "delete", "give", "trust", "untrust", "setspawn", "fee", "flag", "treasury", "deposit", "withdraw"), args[1]);
            if ((args.length == 4 && args[1].equalsIgnoreCase("create")) || (args.length == 3 && Set.of("give", "trust", "untrust").contains(args[1].toLowerCase(Locale.ROOT))))
                return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[args.length-1]);
            if (args.length == 3 && Set.of("delete", "claim").contains(args[1].toLowerCase(Locale.ROOT)))
                return matching(book.list().stream().flatMap(l -> l.estates.values().stream()).map(e -> e.name).toList(), args[2]);
            if (args.length == 3 && args[1].equalsIgnoreCase("flag")) return matching(List.of("public"), args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("flag")) return matching(List.of("on", "off"), args[3]);
        }
        if (args.length == 2 && sub.equals("lot")) return matching(List.of("give", "revoke"), args[1]);
        if ((args.length == 2 && Set.of("invite", "kick", "owner", "role", "ban", "unban").contains(sub))
                || (args.length == 3 && sub.equals("lot") && args[1].equalsIgnoreCase("give"))
                || (args.length == 3 && sub.equals("admin") && Set.of("vet", "unvet").contains(args[1].toLowerCase(Locale.ROOT)))) {
            return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[args.length - 1]);
        }
        if (args.length == 2 && sub.equals("flag")) return matching(new ArrayList<>(Land.defaultFlags().keySet()), args[1]);
        if (args.length == 3 && sub.equals("flag")) return matching(List.of("on", "off"), args[2]);
        if (args.length == 1) return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("charter")) return List.of("sign", "revoke");
        if (args.length == 3 && args[0].equalsIgnoreCase("charter")) return List.of("council", "republic");
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) return List.of("vet", "unvet", "close", "bypass", "delete");
        if (args.length == 2 && args[0].equalsIgnoreCase("vote") && sender instanceof Player p) {
            List<String> out = new ArrayList<>();
            for (String land : book.landsOf(p.getUniqueId())) {
                for (Election e : gov.data().openElections(land)) e.candidates.forEach(c -> out.add(lands.name(c)));
            }
            return out;
        }
        return List.of();
    }
}
