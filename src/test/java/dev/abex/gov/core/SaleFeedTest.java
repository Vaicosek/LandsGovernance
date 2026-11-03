package dev.abex.gov.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SaleFeedTest {
    private final UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID player = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private Sale sale(long id) { return new Sale(id, 1700000000000L, "world:3:-59:10", owner, null,
            player, Sale.Side.BUY, "diamond", 2, 10.0); }

    @Test void exactWireFieldsWithNullCityAndEscapedNames() {
        String body = SaleFeed.body(sale(7), "cities", "Owner", "CityBot2");
        assertEquals("{\"event_id\":\"cities:7\",\"time_ms\":1700000000000,\"server\":\"cities\","
                + "\"shop\":\"world:3:-59:10\",\"owner_uuid\":\"" + owner + "\",\"owner_name\":\"Owner\","
                + "\"city\":null,\"player_uuid\":\"" + player + "\",\"player_name\":\"CityBot2\","
                + "\"side\":\"BUY\",\"item\":\"diamond\",\"qty\":2,\"price\":10.0}", body);
        String special = "quote\"\n雪";
        Sale sell = new Sale(8, 1, "world:1:2:3", owner, "Prague", player, Sale.Side.SELL, "bread", 1, 4);
        var json = JsonParser.parseString(SaleFeed.body(sell, "cities", special, special)).getAsJsonObject();
        assertEquals(special, json.get("owner_name").getAsString());
        assertEquals(special, json.get("player_name").getAsString());
        assertEquals("Prague", json.get("city").getAsString());
        assertEquals("SELL", json.get("side").getAsString());
    }

    @Test void knownAnswerHmacSignsTimestampDotAndExactUtf8Body() {
        // Independent Node crypto HMAC-SHA256 vector, fixed timestamp and body bytes.
        assertEquals("47ff131d5e60c80986c5d89dd04200c0758da544543a8ee62e169f2ec563f642", SaleFeed.signature("test-key", 1700000000000L, "{\"event_id\":\"cities:1\"}"));
    }

    @Test void backoffDoublesAndCapsWithoutOverflow() {
        long[] expected = {30_000, 60_000, 120_000, 240_000, 480_000, 600_000, 600_000};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], SaleFeed.backoff(i + 1));
        assertEquals(600_000, SaleFeed.backoff(Integer.MAX_VALUE));
    }

    @Test void errorsRetryAndOnly2xxOr409Deliver() {
        for (int status : new int[]{0, 199, 300, 400, 401, 429, 500}) {
            GovData data = new GovData(); SaleFeed queue = new SaleFeed(data, message -> fail(message));
            queue.enqueue(sale(1), "cities", "Owner", "Buyer");
            var pending = queue.due(100).orElseThrow();
            queue.complete(pending, status, 100);
            assertTrue(queue.due(30_099).isEmpty());
            var retry = queue.due(30_100).orElseThrow();
            assertEquals(pending.body(), retry.body());
            assertEquals(pending.eventId(), retry.eventId());
            queue.complete(retry, status, 30_100);
            assertEquals(90_100, data.saleFeed.getFirst().nextAttempt());
        }
        for (int status : new int[]{200, 201, 204, 299, 409}) {
            GovData data = new GovData(); SaleFeed queue = new SaleFeed(data, message -> fail(message));
            queue.enqueue(sale(1), "cities", "Owner", "Buyer");
            queue.complete(queue.due(0).orElseThrow(), status, 0);
            assertTrue(data.saleFeed.isEmpty());
        }
    }

    @Test void cappedQueueWarnsDropsOldestAndPersistsRetry(@TempDir Path dir) throws Exception {
        GovData data = new GovData(); List<String> warnings = new ArrayList<>();
        SaleFeed queue = new SaleFeed(data, warnings::add);
        queue.enqueue(sale(1), "cities", "Owner", "Buyer");
        var inFlight = queue.due(0).orElseThrow();
        for (int i = 2; i <= 5001; i++) queue.enqueue(sale(i), "cities", "Owner", "Buyer");
        assertEquals(5000, data.saleFeed.size()); assertEquals(1, warnings.size());
        assertEquals("cities:2", data.saleFeed.getFirst().eventId());
        queue.complete(inFlight, 500, 10);
        assertEquals(5000, data.saleFeed.size());
        queue.complete(queue.due(0).orElseThrow(), 500, 10);
        JsonStore store = new JsonStore(dir.resolve("data.json")); store.save(data);
        GovData loaded = store.load();
        assertEquals(data.saleFeed, loaded.saleFeed);
        assertEquals(30_010, loaded.saleFeed.getFirst().nextAttempt());
        Files.writeString(dir.resolve("old.json"), "{}");
        assertTrue(new JsonStore(dir.resolve("old.json")).load().saleFeed.isEmpty());
    }

    @Test void successfulBuyAndSellQueueOnceButRejectedTradesDoNot() {
        GovData data = new GovData();
        LandBook book = new LandBook(data, Phase1Test.roles(), new ClaimSettings(3, 0));
        var money = new Phase1Test.FakeEconomy();
        money.deposit(owner, 100); money.deposit(player, 100);
        SaleFeed feed = new SaleFeed(data, message -> fail(message));
        ShopService shops = new ShopService(data, book, money, sale -> feed.enqueue(sale, "cities", "Owner", "Buyer"));
        String key = "world:3:-59:10";
        assertTrue(shops.create(owner, key, new ChunkPos("world", 0, 0), "diamond", 2, 10, 4, false, 0).ok());
        assertFalse(shops.buy(owner, key, 1).ok());
        assertFalse(shops.sell(player, "missing", 1).ok());
        assertTrue(data.saleFeed.isEmpty());
        assertTrue(shops.buy(player, key, 2).ok());
        assertTrue(shops.sell(player, key, 2).ok());
        assertEquals(2, data.saleFeed.size());
        assertEquals("cities:1", data.saleFeed.getFirst().eventId());
        assertEquals("cities:2", data.saleFeed.getLast().eventId());
    }
}
