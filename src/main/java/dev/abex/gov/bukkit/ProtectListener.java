package dev.abex.gov.bukkit;

import dev.abex.gov.core.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;

import java.util.List;
import java.util.UUID;

final class ProtectListener implements Listener {
    private final LandBook book;
    private final ElectionService gov;
    private final NativeLands lands;
    private final GovPlugin plugin;

    ProtectListener(LandBook book, ElectionService gov, NativeLands lands, GovPlugin plugin) {
        this.book = book;
        this.gov = gov;
        this.lands = lands;
        this.plugin = plugin;
    }

    static ChunkPos chunk(Location location) {
        return new ChunkPos(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private boolean denied(Player player, Location location, Perm perm) {
        if (plugin.bypass.contains(player.getUniqueId())) return false;
        ChunkPos pos = chunk(location);
        if (book.can(player.getUniqueId(), pos, perm)) return false;
        book.landAt(pos).ifPresent(l -> player.sendActionBar(Component.text("This is " + l.name + "'s city")));
        return true;
    }

    private boolean claimed(Block block) { return book.landAt(chunk(block.getLocation())).isPresent(); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) e.setCancelled(true);
        if (e instanceof BlockMultiPlaceEvent multi) {
            for (var state : multi.getReplacedBlockStates()) {
                if (denied(e.getPlayer(), state.getLocation(), Perm.BUILD)) e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEmpty(PlayerBucketEmptyEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFill(PlayerBucketFillEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    // Interactions can start cancelled when vanilla has no action; still deny both item and block use.
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if (block == null) return;
        String type = block.getType().name();
        Perm perm = null;
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            var state = block.getState();
            if (state instanceof Container || state instanceof Lectern || state instanceof Jukebox) perm = Perm.CONTAINERS;
            else if (type.endsWith("_DOOR") || type.endsWith("_TRAPDOOR") || type.endsWith("_FENCE_GATE")
                    || type.endsWith("_BUTTON") || type.equals("LEVER") || type.endsWith("_BED")) perm = Perm.INTERACT;
            // repeaters, comparators, note blocks, signs, cake, cauldrons...: changing them is building
            else if (block.getType().isInteractable()) perm = Perm.BUILD;
        } else if (e.getAction() == Action.PHYSICAL) {
            if (type.endsWith("_PRESSURE_PLATE") || type.equals("TRIPWIRE")) perm = Perm.INTERACT;
            else if (type.equals("FARMLAND") || type.equals("TURTLE_EGG")) perm = Perm.BUILD;
        }
        if (perm != null && denied(e.getPlayer(), block.getLocation(), perm)) e.setCancelled(true);
    }

    private static boolean protectedEntity(Entity entity) {
        return entity instanceof ItemFrame || entity instanceof ArmorStand || entity instanceof Animals;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (protectedEntity(e.getRightClicked()) && denied(e.getPlayer(), e.getRightClicked().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent e) {
        if (protectedEntity(e.getRightClicked()) && denied(e.getPlayer(), e.getRightClicked().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (denied(e.getPlayer(), e.getRightClicked().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    private static Player player(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player player = player(e.getDamager());
        if (player != null && e.getEntity() instanceof Player target
                && !plugin.bypass.contains(player.getUniqueId())
                && !plugin.realms.pvp(player.getUniqueId(), target.getUniqueId(), chunk(player.getLocation()), chunk(target.getLocation()),
                    book.can(chunk(target.getLocation()), "pvp") && book.can(chunk(player.getLocation()), "pvp"), System.currentTimeMillis()))
            e.setCancelled(true);
        if (player != null && !(e.getEntity() instanceof Monster) && !(e.getEntity() instanceof Player)
                && denied(player, e.getEntity().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        Player player = player(e.getRemover());
        if (player != null && denied(player, e.getEntity().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    /** Armor stands, boats, minecarts, end crystals. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() != null && denied(e.getPlayer(), e.getEntity().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleBreak(org.bukkit.event.vehicle.VehicleDestroyEvent e) {
        Player player = e.getAttacker() == null ? null : player(e.getAttacker());
        if (player != null && denied(player, e.getVehicle().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        if (e.getPlayer() != null && denied(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { e.blockList().removeIf(block -> !book.can(chunk(block.getLocation()), "explosions")); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::claimed); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        String source = e.getSource().getType().name();
        if ((source.equals("FIRE") || source.equals("SOUL_FIRE")) && !book.can(chunk(e.getBlock().getLocation()), "fire")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (!book.can(chunk(e.getBlock().getLocation()), "fire")) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null && plugin.bypass.contains(e.getPlayer().getUniqueId())) return;
        if (!book.can(chunk(e.getBlock().getLocation()), "fire")) e.setCancelled(true);
        else if (e.getPlayer() != null && denied(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMonsterSpawn(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof Monster
                && (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER
                    || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER)
                && !book.can(chunk(e.getLocation()), "monsters")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderman(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Enderman && claimed(e.getBlock())) e.setCancelled(true);
    }

    private boolean crosses(Block piston, List<Block> blocks, BlockFace direction) {
        ChunkPos origin = chunk(piston.getLocation());
        for (Block block : blocks) {
            if (!book.sameClaim(origin, chunk(block.getLocation()))
                    || !book.sameClaim(origin, chunk(block.getRelative(direction).getLocation()))) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent e) {
        if (crosses(e.getBlock(), e.getBlocks(), e.getDirection())
                || !book.sameClaim(chunk(e.getBlock().getLocation()), chunk(e.getBlock().getRelative(e.getDirection()).getLocation()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent e) {
        // Read the piston facing: retraction moves blocks back toward its base.
        BlockFace direction = ((Directional) e.getBlock().getBlockData()).getFacing().getOppositeFace();
        if (crosses(e.getBlock(), e.getBlocks(), direction)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (claimed(e.getToBlock()) && !book.sameClaim(chunk(e.getBlock().getLocation()), chunk(e.getToBlock().getLocation()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        ChunkPos from = chunk(e.getFrom()), to = chunk(e.getTo());
        if (from.equals(to) || book.sameClaim(from, to)) return;
        Land land = book.landAt(to).orElse(null);
        String message = "Wilderness";
        if (land != null) {
            UUID plot = land.plots.get(to.key());
            Estate estate = book.estateAt(to).orElse(null);
            message = estate != null ? estate.name + " (" + land.name + ")"
                    : plot == null ? land.name : lands.name(plot) + "'s lot";
            Realm realm = plugin.realms.ofCity(land.name).orElse(null);
            if (realm != null) message = land.name + " (" + realm.name + ")" + (estate == null ? "" : " — " + estate.name);
        }
        e.getPlayer().sendActionBar(Component.text(message));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        long now = System.currentTimeMillis();
        for (String land : book.landsOf(e.getPlayer().getUniqueId())) gov.observe(land, e.getPlayer().getUniqueId(), now);
    }
}
