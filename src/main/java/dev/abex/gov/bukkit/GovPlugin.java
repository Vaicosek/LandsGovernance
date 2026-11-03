package dev.abex.gov.bukkit;

import dev.abex.gov.core.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.HexFormat;
import java.util.HashSet;

// Not final: MockBukkit subclasses the plugin class in tests.
public class GovPlugin extends JavaPlugin {
    private JsonStore store;
    private GovData data;
    private ElectionService gov;
    private LandBook book;
    Phase1 features;
    RealmService realms;
    RealmCommands realmCommands;
    ShopCommands shopCommands;
    private ShopFeed shopFeed;
    final Set<UUID> bypass = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new JsonStore(getDataFolder().toPath().resolve("data.json"));
        try {
            data = store.load();
        } catch (IOException e) {
            getLogger().severe("Could not read data.json, refusing to start so nothing gets overwritten: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (data.salt == null) data.salt = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));

        RoleSet roles = roles(getConfig());
        book = new LandBook(data, roles, new ClaimSettings(getConfig().getInt("claims.base-chunks", 8),
                getConfig().getInt("claims.chunks-per-member", 4)), flags(getConfig()));
        NativeLands lands = new NativeLands(book, u -> Bukkit.getOfflinePlayer(u).getName(), (land, message) -> {
            book.land(land).ifPresent(l -> l.members.keySet().forEach(u -> {
                var player = Bukkit.getPlayer(u);
                if (player != null) player.sendMessage(Msg.info(message));
            }));
            getLogger().info("[" + land + "] " + message);
        });
        gov = new ElectionService(data, lands, settings(getConfig()));
        book.setGuard(gov.guard());

        EconomyPort economy = getServer().getPluginManager().isPluginEnabled("Vault") ? VaultEconomy.find() : null;
        realms = new RealmService(data, book, gov, economy, realmSettings(getConfig()), realmRoles(getConfig()),
                u -> { String name = Bukkit.getOfflinePlayer(u).getName(); return name == null ? "Unknown player" : name; },
                lands::announce, message -> getLogger().warning(message));
        features = new Phase1(this, book, gov, lands, economy);
        realmCommands = new RealmCommands(this, book, realms);
        getServer().getPluginManager().registerEvents(realmCommands, this);
        shopFeed = new ShopFeed(this, data);
        shopCommands = new ShopCommands(this, new ShopService(data, book, economy, shopFeed::record), data, shopFeed);
        getServer().getPluginManager().registerEvents(shopCommands, this);
        features.tickDay();
        save();
        getServer().getPluginManager().registerEvents(features, this);
        getServer().getPluginManager().registerEvents(new ProtectListener(book, gov, lands, this), this);
        GovCommand cmd = new GovCommand(this, gov, lands, book);
        PluginCommand pc = getCommand("c");
        pc.setExecutor(cmd);
        pc.setTabCompleter(cmd);

        long minute = 20L * 60;
        getServer().getScheduler().runTaskTimer(this, () -> {
            gov.tick(System.currentTimeMillis());
            realms.tick(System.currentTimeMillis());
            features.tickDay();
            features.refreshChatColors();
            save();
        }, minute, minute);
        getServer().getScheduler().runTaskTimer(this, this::sweepAll, 20L * 30, 5 * minute);
        getLogger().info("Claims, elections, vetting and charters are on.");
    }

    @Override
    public void onDisable() {
        if (shopFeed != null) shopFeed.stop();
        if (data != null) save();
    }

    static Map<String, Boolean> flags(FileConfiguration config) {
        Map<String, Boolean> flags = Land.defaultFlags();
        flags.replaceAll((name, value) -> config.getBoolean("flags." + name, value));
        return flags;
    }

    static RoleSet roles(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("roles");
        if (section == null) throw new IllegalArgumentException("Configure roles from highest to lowest.");
        LinkedHashMap<String, Set<Perm>> roles = new LinkedHashMap<>();
        for (String role : section.getKeys(false)) {
            Set<Perm> perms = EnumSet.noneOf(Perm.class);
            for (String perm : section.getStringList(role)) perms.add(Perm.valueOf(perm.toUpperCase(Locale.ROOT)));
            roles.put(role, perms);
        }
        return RoleSet.of(roles);
    }

    static RealmSettings realmSettings(FileConfiguration c) {
        Set<Perm> ally = EnumSet.noneOf(Perm.class);
        for (String value : c.getStringList("relations.ally-perms")) ally.add(Perm.valueOf(value.toUpperCase(Locale.ROOT)));
        if (!c.contains("relations.ally-perms")) ally.add(Perm.INTERACT);
        return new RealmSettings(c.getDouble("realms.create-cost", 1000), c.getDouble("realms.max-tax", 100),
                c.getInt("realms.unpaid-days-kick", 3), c.getInt("realms.term-days", 14), c.getInt("realms.join-tenure-days", 7),
                c.getDouble("realms.election-withdraw-cap", 100), c.getDouble("realms.spawn-fee", 0),
                c.getInt("relations.truce-days", 3), c.getInt("relations.enemy-delay-hours", 24), c.getBoolean("relations.enemy-pvp", true), ally);
    }

    static RoleSet realmRoles(FileConfiguration c) {
        ConfigurationSection section = c.getConfigurationSection("realms.roles");
        // A config from before realms has no section; Bukkit then hands back an empty one built from the jar defaults.
        if (section == null || section.getKeys(false).isEmpty()) return RealmService.defaultRoles();
        LinkedHashMap<String, Set<Perm>> roles = new LinkedHashMap<>();
        for (String role : section.getKeys(false)) {
            Set<Perm> perms = EnumSet.noneOf(Perm.class);
            for (String value : section.getStringList(role)) perms.add(Perm.valueOf(value.toUpperCase(Locale.ROOT)));
            roles.put(role, perms);
        }
        return RoleSet.of(roles);
    }

    void observeAll(long now) {
        for (Land land : data.lands.values()) gov.sweep(land.name, now, false);
        features.refreshChatColors();
    }

    static Settings settings(FileConfiguration c) {
        return new Settings(
                c.getInt("tenure-days", 7),
                c.getInt("vetting.min-playtime-hours", 10),
                c.getBoolean("vetting.one-vetted-account-per-ip", true),
                c.getInt("elections.default-hours", 48),
                c.getInt("elections.min-voters", 3),
                c.getInt("charter.term-days", 30),
                c.getInt("charter.revoke-notice-days", 14),
                new HashSet<>(c.getStringList("voting-roles")));
    }

    /** Refresh tenure of every member of every land. */
    void sweepAll() {
        Collection<Land> all = data.lands.values();
        if (all.isEmpty()) return;
        boolean grandfather = !data.bootstrapped && getConfig().getBoolean("grandfather-existing-members", true);
        long now = System.currentTimeMillis();
        for (Land l : all) gov.sweep(l.name, now, grandfather);
        if (!data.bootstrapped) {
            data.bootstrapped = true;
            getLogger().info("First tenure sweep done over " + all.size() + " cities (grandfathered: " + grandfather + ").");
        }
        save();
    }

    String ipHash(String ip) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((data.salt + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void save() {
        try {
            store.save(data);
        } catch (IOException e) {
            getLogger().severe("Could not save data.json: " + e);
        }
    }
}
