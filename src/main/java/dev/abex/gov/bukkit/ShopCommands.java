package dev.abex.gov.bukkit;

import dev.abex.gov.core.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.Material;
import org.bukkit.block.*;

import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** /c shop and the chest/sign side of shops: trading by right-click, protection, hoppers, explosions. */
final class ShopCommands implements Listener {
    private static final Set<Material> CONTAINERS = EnumSet.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);
    private final GovPlugin plugin;
    private final ShopService shops;
    private final GovData data;

    ShopCommands(GovPlugin plugin, ShopService shops, GovData data) { this.plugin = plugin; this.shops = shops; this.data = data; }

    private ShopFeed feed;

    ShopCommands(GovPlugin plugin, ShopService shops, GovData data, ShopFeed feed) {
        this(plugin, shops, data);
        this.feed = feed;
    }

    static Component webLink(String base, String token) {
        return Component.text("Open my shops (expires in 24h)")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(base.replaceAll("/+$", "") + "/shops/" + token));
    }

    private void web(Player p, long now) {
        tip(p, "shop-web", "This link opens your shops, stock and recent sales. It is private to you; anyone you share it with can open it. It expires in 24h.");
        plugin.save();
        if (feed == null || !feed.webEnabled()) {
            p.sendMessage(Msg.info("Shop web pages are not configured.")); return;
        }
        String token = ShopWeb.token();
        String base = plugin.getConfig().getString("feed.web_base", "").trim();
        try {
            var uri = java.net.URI.create(base);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            p.sendMessage(Msg.info("Shop web pages are not configured.")); return;
        }
        var snapshot = ShopWeb.snapshot(shops, p.getUniqueId(), p.getName(), feed.server(), token, now,
                s -> {
                    String[] pos = s.key.split(":");
                    var world = plugin.getServer().getWorld(pos[0]);
                    int x = Integer.parseInt(pos[1]), z = Integer.parseInt(pos[3]);
                    // Do not load chunks just to view a page. Unknown stock is explicit.
                    if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) return null;
                    Material mat = Material.matchMaterial(s.item);
                    Block block = world.getBlockAt(x, Integer.parseInt(pos[2]), z);
                    return mat != null && block.getState() instanceof Container c ? count(c.getInventory(), mat) : null;
                }, id -> {
                    String name = Bukkit.getOfflinePlayer(id).getName();
                    return name == null ? "Unknown player" : name;
                });
        p.sendMessage(Msg.info("Preparing your shop page…"));
        feed.web(snapshot.body(), ok -> {
            if (!p.isOnline()) return;
            p.sendMessage(ok ? webLink(base, token) : Msg.info("Could not create your shop page. Try /c shop web again."));
        });
    }

    static String key(Location l) { return l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ(); }

    boolean command(Player p, String sub, String[] args) {
        if (!sub.equals("shop")) return false;
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        long now = System.currentTimeMillis();
        if (action.equals("web")) { web(p, now); return true; }
        if (action.equals("log")) { log(p, args, now); return true; }
        Block target = p.getTargetBlockExact(5);
        // Looking at a shop's sign counts as looking at its chest.
        Block chest = target == null ? null : Optional.ofNullable(shopChest(target)).orElse(chestOf(target));
        switch (action) {
            case "create" -> {
                if (args.length < 5) { p.sendMessage(Msg.info("Usage: /c shop create <qty> <buy price|-> <sell price|-> [city], holding the item, looking at a chest")); return true; }
                if (chest == null) { p.sendMessage(Msg.info("Look at a chest or barrel.")); return true; }
                if (chest.getState() instanceof Chest c && c.getInventory().getHolder() instanceof DoubleChest) { p.sendMessage(Msg.info("Use a single chest.")); return true; }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) { p.sendMessage(Msg.info("Hold the item the shop will trade.")); return true; }
                int qty; double buy, sell;
                try { qty = Integer.parseInt(args[2]); buy = price(args[3]); sell = price(args[4]); }
                catch (NumberFormatException e) { p.sendMessage(Msg.info("Quantity and prices must be numbers, or - for none.")); return true; }
                boolean city = args.length > 5 && args[5].equalsIgnoreCase("city");
                String item = hand.getType().getKey().getKey();
                Result r = shops.create(p.getUniqueId(), key(chest.getLocation()), ProtectListener.chunk(chest.getLocation()), item, qty, buy, sell, city, now);
                p.sendMessage(Msg.of(r));
                if (r.ok()) { shops.at(key(chest.getLocation())).ifPresent(s -> placeSign(s, chest, p)); tip(p, "shop-owner",
                        "Your shop is open. Others right-click it to buy and sneak + right-click to sell. Only you can open or break it. Keep it stocked, and keep money for what it buys. /c shop remove closes it."); }
            }
            case "remove" -> {
                if (chest == null) { p.sendMessage(Msg.info("Look at your shop chest.")); return true; }
                Optional<Shop> shop = shops.at(key(chest.getLocation()));
                Result r = shops.remove(p.getUniqueId(), key(chest.getLocation()));
                if (r.ok()) shop.ifPresent(this::clearSign);
                p.sendMessage(Msg.of(r));
            }
            case "buy", "sell" -> {
                if (chest == null || shops.at(key(chest.getLocation())).isEmpty()) { p.sendMessage(Msg.info("Look at a shop.")); return true; }
                trade(p, chest, action.equals("sell"));
            }
            default -> {
                tip(p, "shop", "Shops: put a chest down, hold the item, look at the chest and type /c shop create <qty> <buy> <sell>. Add 'city' to trade from your city treasury.");
                p.sendMessage(Msg.info("/c shop create <qty> <buy|-> <sell|-> [city] · remove · buy · sell · web · log [page] (staff: log <player> [page])"));
                for (Shop s : shops.of(p.getUniqueId())) p.sendMessage(Msg.info(s.qty + " " + s.item + "  buy " + show(s.buy) + "  sell " + show(s.sell)
                        + (s.city == null ? "" : "  (" + s.city + ")") + "  at " + s.key));
            }
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        List<Sale> unread = shops.unread(p.getUniqueId());
        List<String> low = new ArrayList<>();
        for (Shop shop : shops.of(p.getUniqueId())) {
            String[] pos = shop.key.split(":");
            var world = plugin.getServer().getWorld(pos[0]);
            int x = Integer.parseInt(pos[1]), z = Integer.parseInt(pos[3]);
            if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Material mat = Material.matchMaterial(shop.item);
            if (mat != null && world.getBlockAt(x, Integer.parseInt(pos[2]), z).getState() instanceof Container chest
                    && count(chest.getInventory(), mat) < shop.qty) low.add(shop.item + " · " + ShopLog.location(shop.key));
        }
        if (unread.isEmpty() && low.isEmpty()) return;
        if (!unread.isEmpty()) {
            double income = unread.stream().filter(sale -> sale.side() == Sale.Side.BUY).mapToDouble(Sale::price).sum();
            double expense = unread.stream().filter(sale -> sale.side() == Sale.Side.SELL).mapToDouble(Sale::price).sum();
            p.sendMessage(Msg.info("While you were away: " + unread.size() + " sales, +" + coins(income) + " / -" + coins(expense) + " ")
                    .append(Msg.button("Shop log", "/c shop log", "Read your shop sales")));
        }
        for (Sale sale : unread.stream().limit(10).toList()) sendSale(p, sale, System.currentTimeMillis());
        for (String stock : low) p.sendMessage(Msg.info("Low stock: " + stock));
        tip(p, "shop-summary", "This summary shows unread shop income and expenses, plus shops needing stock. /c shop log shows all your sales and marks them read.");
        plugin.save();
    }

    private void log(Player p, String[] args, long now) {
        UUID viewer = p.getUniqueId();
        String base = "/c shop log";
        int page = 1;
        if (args.length > 4) { p.sendMessage(Msg.info("Usage: /c shop log [page], or /c shop log <player> [page] for staff.")); return; }
        if (args.length > 2) {
            if (args[2].matches("[+-]?[0-9]+")) {
                if (args.length > 3) { p.sendMessage(Msg.info("Usage: /c shop log [page]")); return; }
                try { page = Integer.parseInt(args[2]); }
                catch (NumberFormatException ex) { p.sendMessage(Msg.info("Invalid page.")); return; }
            } else {
                if (!p.hasPermission("cities.admin")) { p.sendMessage(Msg.info("Only staff can read another player's shop log.")); return; }
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[2]);
                if (target == null) { p.sendMessage(Msg.info("No known player called " + args[2] + ".")); return; }
                viewer = target.getUniqueId();
                base += " " + args[2];
                if (args.length > 3) try { page = Integer.parseInt(args[3]); }
                catch (NumberFormatException ex) { p.sendMessage(Msg.info("Invalid page.")); return; }
            }
        }
        List<Sale> rows = shops.salesOf(viewer);
        int pages = Math.max(1, (rows.size() + 9) / 10);
        if (page < 1 || page > pages) { p.sendMessage(Msg.info("Choose a page from 1 to " + pages + ".")); return; }
        p.sendMessage(Msg.info("Shop log — Page " + page + "/" + pages));
        if (rows.isEmpty()) p.sendMessage(Msg.info("No shop sales yet."));
        for (Sale sale : rows.subList((page - 1) * 10, Math.min(page * 10, rows.size()))) {
            sendSale(p, sale, now);
        }
        Component navigation = Component.empty();
        if (page > 1) navigation = navigation.append(Msg.button("Previous", base + " " + (page - 1), "Previous sales"));
        if (page < pages) navigation = navigation.append(Component.text(" ")).append(Msg.button("Next", base + " " + (page + 1), "More sales"));
        if (page > 1 || page < pages) p.sendMessage(navigation);
        // Staff inspection must not clear the target's unread notification.
        if (viewer.equals(p.getUniqueId())) shops.markRead(viewer, now);
    }

    private void sendSale(Player p, Sale sale, long now) {
        String name = Bukkit.getOfflinePlayer(sale.player()).getName();
        p.sendMessage(Msg.info(ShopLog.line(sale, name == null ? sale.player().toString() : name,
                now, java.time.ZoneId.systemDefault())));
    }

    private static String coins(double amount) { return String.format(Locale.ROOT, "%.2f", amount); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null || e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block chest = shopChest(e.getClickedBlock());
        if (chest == null) return;
        Player p = e.getPlayer();
        ShopClick.Action action = ShopClick.decide(shops.canAccess(p.getUniqueId(), key(chest.getLocation())),
                plugin.bypass.contains(p.getUniqueId()), p.isSneaking());
        if (action == ShopClick.Action.OPEN) return;
        e.setCancelled(true);
        trade(p, chest, action == ShopClick.Action.SELL);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) {
        Block chest = shopChest(e.getBlock());
        if (chest == null) return;
        String key = key(chest.getLocation());
        if (!shops.canAccess(e.getPlayer().getUniqueId(), key) && !plugin.bypass.contains(e.getPlayer().getUniqueId())) {
            e.setCancelled(true); e.getPlayer().sendMessage(Msg.info("That's someone's shop.")); return;
        }
        Shop shop = shops.at(key).orElseThrow();
        if (e.getBlock().equals(chest)) { clearSign(shop); shops.forget(key); e.getPlayer().sendMessage(Msg.info("Shop closed.")); }
        else shop.sign = null;
        plugin.save();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent e) {
        // A second chest next to a shop would make a double chest that opens the shop's half.
        if (!CONTAINERS.contains(e.getBlockPlaced().getType())) return;
        for (BlockFace f : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST))
            if (shops.at(key(e.getBlockPlaced().getRelative(f).getLocation())).isPresent()) {
                e.setCancelled(true); e.getPlayer().sendMessage(Msg.info("Not next to a shop chest.")); return;
            }
    }

    @EventHandler public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> shopChest(b) != null); }
    @EventHandler public void onExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> shopChest(b) != null); }
    @EventHandler public void onBurn(BlockBurnEvent e) { if (shopChest(e.getBlock()) != null) e.setCancelled(true); }

    @EventHandler
    public void onHopper(InventoryMoveItemEvent e) {
        if (isShop(e.getSource())) e.setCancelled(true);
    }

    private boolean isShop(Inventory inv) {
        Location l = inv.getLocation();
        return l != null && shops.at(key(l)).isPresent();
    }

    private void trade(Player p, Block chest, boolean selling) {
        Shop shop = shops.at(key(chest.getLocation())).orElseThrow();
        tip(p, "shop-buyer", "This is " + (shop.city == null ? "a player shop" : "a shop of the city " + shop.city) + ". Right-click buys " + shop.qty + " " + shop.item + " for " + show(shop.buy)
                + "; sneak + right-click sells to it for " + show(shop.sell) + ".");
        Material mat = Material.matchMaterial(shop.item);
        if (mat == null || !(chest.getState() instanceof Container container)) { p.sendMessage(Msg.info("This shop is broken; tell its owner.")); return; }
        Inventory stock = container.getInventory(), mine = p.getInventory();
        Inventory from = selling ? mine : stock, to = selling ? stock : mine;
        if (!selling && !shop.sells()) { p.sendMessage(Msg.info("This shop doesn't sell.")); return; }
        if (selling && !shop.buys()) { p.sendMessage(Msg.info("This shop doesn't buy.")); return; }
        if (count(from, mat) < shop.qty) { p.sendMessage(Msg.info(selling ? "You don't have " + shop.qty + " plain " + shop.item + "." : "Out of stock.")); return; }
        if (!fits(to, mat, shop.qty)) { p.sendMessage(Msg.info(selling ? "The shop is full." : "Your inventory is full.")); return; }
        Result r = selling ? shops.sell(p.getUniqueId(), shop.key, System.currentTimeMillis()) : shops.buy(p.getUniqueId(), shop.key, System.currentTimeMillis());
        if (r.ok()) { take(from, mat, shop.qty); to.addItem(new ItemStack(mat, shop.qty)); plugin.save(); }
        p.sendMessage(Msg.of(r));
    }

    /** Only plain items trade, so a renamed or enchanted one can't be passed off as the shop's item. */
    private static boolean plain(ItemStack s, Material mat) { return s != null && s.getType() == mat && s.isSimilar(new ItemStack(mat)); }
    private static int count(Inventory inv, Material mat) {
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) if (plain(s, mat)) n += s.getAmount();
        return n;
    }
    private static void take(Inventory inv, Material mat, int qty) {
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && qty > 0; i++) {
            ItemStack s = contents[i];
            if (!plain(s, mat)) continue;
            int n = Math.min(qty, s.getAmount()); qty -= n; s.setAmount(s.getAmount() - n);
            if (s.getAmount() == 0) contents[i] = null;
        }
        inv.setStorageContents(contents);
    }
    private static boolean fits(Inventory inv, Material mat, int qty) {
        int room = 0, max = mat.getMaxStackSize();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) room += max;
            else if (plain(s, mat)) room += max - s.getAmount();
        }
        return room >= qty;
    }

    /** The shop chest a clicked block belongs to: the chest itself or its sign. */
    private Block shopChest(Block b) {
        if (shops.at(key(b.getLocation())).isPresent()) return b;
        if (!(b.getState() instanceof Sign)) return null;
        String k = key(b.getLocation());
        return data.shops.values().stream().filter(s -> k.equals(s.sign)).findFirst().map(s -> at(s.key)).orElse(null);
    }
    private Block at(String key) {
        String[] p = key.split(":");
        var world = plugin.getServer().getWorld(p[0]);
        return world == null ? null : world.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }
    private static Block chestOf(Block b) { return CONTAINERS.contains(b.getType()) ? b : null; }

    private void placeSign(Shop shop, Block chest, Player p) {
        // On top, not on the chest's side: the side toward the player can be where they stand.
        Block spot = chest.getRelative(BlockFace.UP);
        if (!spot.getType().isAir()) return;
        spot.setType(Material.OAK_SIGN);
        if (spot.getBlockData() instanceof org.bukkit.block.data.Rotatable r) { r.setRotation(p.getFacing().getOppositeFace()); spot.setBlockData(r); }
        if (spot.getState() instanceof Sign sign) {
            var side = sign.getSide(Side.FRONT);
            side.line(0, Component.text(shop.city == null ? "[Shop]" : "[" + shop.city + "]"));
            side.line(1, Component.text(shop.qty + " " + shop.item));
            side.line(2, Component.text("B " + show(shop.buy) + " S " + show(shop.sell)));
            side.line(3, Component.text(p.getName()));
            sign.setWaxed(true);
            sign.update();
            shop.sign = key(spot.getLocation());
        }
    }
    private void clearSign(Shop shop) {
        if (shop.sign == null) return;
        Block b = at(shop.sign);
        if (b != null && b.getState() instanceof Sign) b.setType(Material.AIR);
        shop.sign = null;
    }

    private void tip(Player p, String id, String text) {
        if (data.tipsSeen.add(p.getUniqueId() + "|" + id)) p.sendMessage(Msg.info("Tip: " + text));
    }
    private static double price(String s) { return s.equals("-") ? -1 : Double.parseDouble(s); }
    private static String show(double price) { return price < 0 ? "-" : String.valueOf(price); }
}
