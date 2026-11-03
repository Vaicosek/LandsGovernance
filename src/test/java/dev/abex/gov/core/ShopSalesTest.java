package dev.abex.gov.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopSalesTest {
    final UUID owner = UUID.randomUUID(), buyer = UUID.randomUUID(), mayor = UUID.randomUUID(), member = UUID.randomUUID();
    final String key = "world:300:64:5", cityKey = "world:1:64:1";
    GovData data;
    LandBook book;
    Phase1Test.FakeEconomy money;
    ShopService shops;

    @BeforeEach void setup() {
        data = new GovData();
        book = new LandBook(data, Phase1Test.roles(), new ClaimSettings(3, 0));
        money = new Phase1Test.FakeEconomy();
        book.setEconomy(money);
        shops = new ShopService(data, book, money);
        money.deposit(owner, 100); money.deposit(buyer, 100);
        assertTrue(shops.create(owner, key, new ChunkPos("world", 18, 0), "diamond", 2, 10, 4, false, 0).ok());
    }

    @Test void snapshotsBothDirectionsAndKeepsIdenticalTradesDistinctAfterRemoval() {
        assertTrue(shops.buy(buyer, key, 100).ok());
        assertTrue(shops.buy(buyer, key, 100).ok());
        assertTrue(shops.sell(buyer, key, 101).ok());
        Sale first = data.sales.getFirst();
        assertEquals(new Sale(first.id(), 100, key, owner, null, buyer, Sale.Side.BUY, "diamond", 2, 10), first);
        assertNotEquals(first.id(), data.sales.get(1).id());
        assertEquals(Sale.Side.SELL, data.sales.getLast().side());
        assertEquals(4, data.sales.getLast().price());
        shops.remove(owner, key);
        assertEquals(3, shops.salesOf(owner).size());
        assertEquals(data.sales.getLast(), shops.salesOf(owner).getFirst());
        assertTrue(shops.salesOf(buyer).isEmpty());
    }

    @Test void failedTradesAndRefundsNeverAppend() {
        assertFalse(shops.buy(owner, key, 1).ok());
        assertFalse(shops.buy(buyer, "missing", 1).ok());
        money.withdraw(buyer, 100);
        assertFalse(shops.buy(buyer, key, 1).ok());
        money.withdraw(owner, 100);
        assertFalse(shops.sell(buyer, key, 1).ok());
        money.deposit(buyer, 100);
        EconomyPort broken = new EconomyPort() {
            public double balance(UUID id) { return money.balance(id); }
            public boolean withdraw(UUID id, double amount) { return money.withdraw(id, amount); }
            public void deposit(UUID id, double amount) {
                if (id.equals(owner)) throw new IllegalStateException("payment down");
                money.deposit(id, amount);
            }
        };
        assertFalse(new ShopService(data, book, broken).buy(buyer, key, 1).ok());
        assertEquals(100, money.balance(buyer));
        assertTrue(data.sales.isEmpty());
    }

    @Test void readCursorSurvivesReloadAndDoesNotLoseSameMillisecondTrade(@TempDir Path dir) throws Exception {
        shops.buy(buyer, key, 10);
        assertEquals(1, shops.unread(owner).size());
        shops.markRead(owner, 10);
        assertTrue(shops.unread(owner).isEmpty());
        JsonStore store = new JsonStore(dir.resolve("data.json"));
        store.save(data);
        GovData loaded = store.load();
        assertEquals(data.sales, loaded.sales);
        assertEquals(10L, loaded.seen.get(owner).longValue());
        ShopService restored = new ShopService(loaded, new LandBook(loaded, Phase1Test.roles(), new ClaimSettings(3, 0)), money);
        restored.buy(buyer, key, 10);
        assertEquals(1, restored.unread(owner).size());
        assertTrue(loaded.sales.getLast().id() > loaded.sales.getFirst().id());
    }

    @Test void capsRowsWithoutReusingIdsEvenAfterReload(@TempDir Path dir) throws Exception {
        Shop shop = data.shops.get(key); shop.buy = 0;
        for (int i = 0; i < 10_005; i++) assertTrue(shops.buy(buyer, key, i).ok());
        assertEquals(10_000, data.sales.size());
        assertEquals(6, data.sales.getFirst().id());
        JsonStore store = new JsonStore(dir.resolve("data.json")); store.save(data);
        GovData loaded = store.load();
        ShopService restored = new ShopService(loaded, new LandBook(loaded, Phase1Test.roles(), new ClaimSettings(3, 0)), money);
        restored.buy(buyer, key, 10_006);
        assertEquals(10_000, loaded.sales.size());
        assertEquals(10_006, loaded.sales.getLast().id());
        assertEquals(10_000, loaded.sales.stream().map(Sale::id).distinct().count());
    }

    @Test void cityHistoryUsesTreasuryRightsAndIndependentReaders() {
        Spot spot = new Spot("world", 1, 70, 1, 0, 0);
        book.create(mayor, "City", spot, 0);
        book.invite(mayor, "City", member, 0); book.join(member, "City", 0);
        assertTrue(shops.create(mayor, cityKey, spot.chunk(), "bread", 1, 5, 2, true, 0).ok());
        assertFalse(shops.sell(buyer, cityKey, 1).ok());
        assertTrue(data.sales.isEmpty());
        shops.buy(buyer, cityKey, 2); shops.sell(buyer, cityKey, 3);
        assertEquals("City", data.sales.getFirst().city());
        assertEquals(2, shops.unread(mayor).size());
        assertTrue(shops.salesOf(member).isEmpty());
        book.land("City").orElseThrow().members.put(member, book.roles().top());
        assertEquals(2, shops.unread(member).size());
        shops.markRead(member, 3);
        assertTrue(shops.unread(member).isEmpty());
        assertEquals(2, shops.unread(mayor).size());
        shops.remove(mayor, cityKey);
        assertEquals(2, shops.salesOf(mayor).size());
        book.adminDelete("City", 4);
        assertTrue(shops.salesOf(mayor).isEmpty());
    }

    @Test void oldJsonGetsEmptyHistoryAndCanRecordFirstTrade(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("old.json"); Files.writeString(path, "{}");
        GovData loaded = new JsonStore(path).load();
        ShopService restored = new ShopService(loaded, new LandBook(loaded, Phase1Test.roles(), new ClaimSettings(3, 0)), money);
        restored.create(owner, key, new ChunkPos("world", 18, 0), "diamond", 1, 0, -1, false, 0);
        assertTrue(restored.buy(buyer, key, 1).ok());
        assertEquals(1, restored.unread(owner).size());
        assertEquals(1, loaded.sales.getFirst().id());
    }
}
