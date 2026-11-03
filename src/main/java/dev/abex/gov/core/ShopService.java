package dev.abex.gov.core;

import java.util.*;

/**
 * Money side of chest shops. The Bukkit layer checks stock and inventory space first, calls buy/sell,
 * and moves the items only when the result is ok.
 */
public final class ShopService {
    private final GovData data;
    private final LandBook book;
    private final EconomyPort economy;

    private final java.util.function.Consumer<Sale> saleRecorded;

    public ShopService(GovData data, LandBook book, EconomyPort economy) {
        this(data, book, economy, sale -> {});
    }

    public ShopService(GovData data, LandBook book, EconomyPort economy, java.util.function.Consumer<Sale> saleRecorded) {
        this.data = data; this.book = book; this.economy = economy; this.saleRecorded = saleRecorded;
    }

    public Optional<Shop> at(String key) { return Optional.ofNullable(data.shops.get(key)); }

    public List<Shop> of(UUID player) {
        return data.shops.values().stream().filter(s -> s.city == null ? s.owner.equals(player)
                : book.has(book.land(s.city).orElse(null), player, Perm.BANK)).toList();
    }

    public Result create(UUID actor, String key, ChunkPos chunk, String item, int qty, double buy, double sell, boolean cityShop, long now) {
        if (data.shops.containsKey(key)) return Result.no("That chest is already a shop.");
        if (qty < 1 || qty > 64) return Result.no("Trade 1 to 64 items at a time.");
        if (!price(buy) || !price(sell) || (buy < 0 && sell < 0)) return Result.no("Set a buy price, a sell price, or both.");
        Land land = book.landAt(chunk).orElse(null);
        if (cityShop && !book.has(land, actor, Perm.BANK)) return Result.no("City shops need treasury rights in the city you stand in.");
        if (!cityShop && !book.can(actor, chunk, Perm.CONTAINERS)) return Result.no("You can't open a shop here.");
        Shop shop = new Shop();
        shop.key = key; shop.chunk = chunk.key(); shop.owner = actor; shop.city = cityShop ? land.name : null;
        shop.item = item; shop.qty = qty; shop.buy = buy; shop.sell = sell; shop.created = now;
        data.shops.put(key, shop);
        return Result.ok(cityShop ? "City shop opened for " + land.name + "." : "Shop opened.");
    }

    /** Owner, or for a city shop anyone with treasury rights; the creator once the city is gone. */
    public boolean canAccess(UUID player, String key) {
        Shop shop = data.shops.get(key);
        if (shop == null) return true;
        if (shop.city == null) return shop.owner.equals(player);
        Land land = book.land(shop.city).orElse(null);
        return land == null ? shop.owner.equals(player) : book.has(land, player, Perm.BANK);
    }

    public Result remove(UUID actor, String key) {
        if (!data.shops.containsKey(key)) return Result.no("No shop there.");
        if (!canAccess(actor, key)) return Result.no("Only the shop's owner can close it.");
        data.shops.remove(key);
        return Result.ok("Shop closed.");
    }

    /** Called when the chest is gone (broken by its owner, staff, or an explosion we let through). */
    public void forget(String key) { data.shops.remove(key); }

    /** Player pays the buy price; the owner or city treasury receives it. */
    public Result buy(UUID player, String key, long now) {
        Shop shop = data.shops.get(key);
        Result open = open(shop, player);
        if (!open.ok()) return open;
        if (!shop.sells()) return Result.no("This shop doesn't sell.");
        if (economy.balance(player) < shop.buy || !economy.withdraw(player, shop.buy)) return Result.no("You can't afford " + shop.buy + ".");
        if (shop.city != null) {
            Bank.record(book.land(shop.city).orElseThrow(), now, player, shop.buy, "Shop sale");
        } else {
            try { economy.deposit(shop.owner, shop.buy); }
            catch (RuntimeException e) { economy.deposit(player, shop.buy); return Result.no("The payment failed; you were refunded."); }
        }
        record(shop, player, Sale.Side.BUY, shop.buy, now);
        return Result.ok("Bought " + shop.qty + " " + shop.item + " for " + shop.buy + ".");
    }

    /** Owner or city treasury pays the sell price to the player. */
    public Result sell(UUID player, String key, long now) {
        Shop shop = data.shops.get(key);
        Result open = open(shop, player);
        if (!open.ok()) return open;
        if (!shop.buys()) return Result.no("This shop doesn't buy.");
        if (shop.city != null) {
            Land land = book.land(shop.city).orElseThrow();
            if (land.balance < shop.sell) return Result.no("The city treasury can't pay for that.");
            try { economy.deposit(player, shop.sell); }
            catch (RuntimeException e) { return Result.no("The payment failed."); }
            Bank.record(land, now, player, -shop.sell, "Shop purchase");
        } else {
            if (economy.balance(shop.owner) < shop.sell || !economy.withdraw(shop.owner, shop.sell)) return Result.no("The owner can't pay for that.");
            try { economy.deposit(player, shop.sell); }
            catch (RuntimeException e) { economy.deposit(shop.owner, shop.sell); return Result.no("The payment failed."); }
        }
        record(shop, player, Sale.Side.SELL, shop.sell, now);
        return Result.ok("Sold " + shop.qty + " " + shop.item + " for " + shop.sell + ".");
    }

    private void record(Shop shop, UUID player, Sale.Side side, double price, long now) {
        Sale sale = new Sale(data.nextSaleId++, now, shop.key, shop.owner, shop.city,
                player, side, shop.item, shop.qty, price);
        data.sales.add(sale);
        saleRecorded.accept(sale);
        if (data.sales.size() > 10_000) data.sales.subList(0, data.sales.size() - 10_000).clear();
    }

    /** Historical ownership survives shop removal; city access follows current treasury rights. */
    public List<Sale> salesOf(UUID viewer) {
        return data.sales.stream().filter(s -> s.city() == null ? s.owner().equals(viewer)
                : book.has(book.land(s.city()).orElse(null), viewer, Perm.BANK))
                .sorted(Comparator.comparingLong(Sale::id).reversed()).toList();
    }

    public List<Sale> unread(UUID viewer) {
        Long id = data.salesSeenId.get(viewer);
        return salesOf(viewer).stream().filter(s -> id != null ? s.id() > id
                : s.time() > data.seen.getOrDefault(viewer, Long.MIN_VALUE)).toList();
    }

    public void markRead(UUID viewer, long now) {
        data.seen.put(viewer, now);
        data.salesSeenId.put(viewer, data.nextSaleId - 1);
    }

    private Result open(Shop shop, UUID player) {
        if (shop == null) return Result.no("No shop there.");
        if (economy == null) return Result.no("No economy plugin installed.");
        if (canAccess(player, shop.key)) return Result.no("That's your own shop.");
        if (shop.city != null && book.land(shop.city).isEmpty()) return Result.no("This shop's city is gone.");
        Land here = data.lands.values().stream().filter(l -> l.chunks.contains(shop.chunk)).findFirst().orElse(null);
        if (here != null && here.banned.contains(player)) return Result.no("You're banned from " + here.name + ".");
        return Result.ok("");
    }

    private static boolean price(double p) { return Double.isFinite(p) && p <= 1_000_000; }
}
