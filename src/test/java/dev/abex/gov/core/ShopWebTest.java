package dev.abex.gov.core;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonParser;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopWebTest {
    @Test void snapshotsOnlyPersonalAndManagedTreasuryShopsWithBothSidesAndLastFiftySales() {
        GovData data = new GovData();
        LandBook book = new LandBook(data, Phase1Test.roles(), new ClaimSettings(3, 0));
        var money = new Phase1Test.FakeEconomy();
        book.setEconomy(money);
        ShopService shops = new ShopService(data, book, money);
        UUID owner = UUID.randomUUID(), mayor = UUID.randomUUID(), buyer = UUID.randomUUID();
        assertTrue(shops.create(owner, "world:300:64:5", new ChunkPos("world", 18, 0), "diamond", 2, 0, 4, false, 0).ok());
        assertTrue(shops.create(buyer, "world:400:64:5", new ChunkPos("world", 25, 0), "iron_ingot", 1, 0, -1, false, 0).ok());
        Spot spot = new Spot("world", 1, 70, 1, 0, 0);
        book.create(mayor, "City", spot, 0);
        book.invite(mayor, "City", owner, 0); book.join(owner, "City", 0);
        shops.create(mayor, "world:1:64:1", spot.chunk(), "bread", 1, 0, -1, true, 0);
        var before = ShopWeb.snapshot(shops, owner, "Owner", "cities", "token", 100, s -> 3, id -> "Buyer");
        assertEquals(2, before.shops().size());
        book.land("City").orElseThrow().members.put(owner, book.roles().top());
        for (int i = 0; i < 55; i++) assertTrue(shops.buy(buyer, "world:300:64:5", i).ok());
        assertTrue(shops.buy(buyer, "world:1:64:1", 55).ok());
        var snapshot = ShopWeb.snapshot(shops, owner, "Owner", "cities", "token", 100,
                s -> s.city == null ? 3 : null, id -> "Buyer");
        assertEquals(3, snapshot.shops().size());
        assertEquals("City", snapshot.shops().getFirst().city());
        assertNull(snapshot.shops().getFirst().stock());
        assertEquals(Set.of("BUY", "SELL"), new HashSet<>(snapshot.shops().stream().map(ShopWeb.Row::side).toList()));
        assertTrue(snapshot.shops().stream().noneMatch(s -> s.item().equals("iron_ingot")));
        assertEquals(50, snapshot.sales().size());
        assertEquals(55, snapshot.sales().getFirst().time_ms());
        assertEquals(6, snapshot.sales().getLast().time_ms());
        assertEquals("Buyer", snapshot.sales().getFirst().buyer());
        var json = JsonParser.parseString(snapshot.body()).getAsJsonObject();
        assertTrue(json.getAsJsonArray("shops").get(0).getAsJsonObject().get("stock").isJsonNull());
        assertEquals(2, json.getAsJsonArray("shops").get(1).getAsJsonObject().get("qty").getAsInt());
    }

    @Test void tokenHas256RandomBitsAndIsUrlSafe() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String token = ShopWeb.token();
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
            assertEquals(32, Base64.getUrlDecoder().decode(token).length);
            assertTrue(tokens.add(token));
        }
    }

    @Test void signedSnapshotMatchesIndependentRestockerVector() {
        String body = new ShopWeb.Snapshot("abcdefghijklmnopqrstuvwxyz0123456789ABCDE_FG",
                "00000000-0000-0000-0000-000000000001", "Vaicos", "cities",
                1700000000000L, List.of(), List.of()).body();
        assertEquals("450325e9298f1d0fd4bddb5cf4c50ced0e969999aa8937b4599414a050b2b969", SaleFeed.signature("k", 1700000000000L, body));
        assertNotEquals(SaleFeed.signature("k", 1700000000000L, body),
                SaleFeed.signature("k", 1700000000000L, body + " "));
    }
}
