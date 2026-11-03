package dev.abex.gov.bukkit;

import dev.abex.gov.core.*;
import org.bukkit.Material;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class ShopBreakPersistenceTest {
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void strangerCannotBreakChestOrSignAndOwnerClosureIsDurable() throws Exception {
        var server = MockBukkit.mock();
        var world = server.addSimpleWorld("world");
        var owner = server.addPlayer();
        var stranger = server.addPlayer();
        GovPlugin plugin = MockBukkit.load(GovPlugin.class);
        var field = GovPlugin.class.getDeclaredField("data"); field.setAccessible(true);
        GovData data = (GovData) field.get(plugin);
        LandBook book = new LandBook(data, GovPlugin.roles(plugin.getConfig()), new ClaimSettings(3, 0));
        ShopService shops = new ShopService(data, book, null);
        var chest = world.getBlockAt(3, 64, 10); chest.setType(Material.CHEST);
        var sign = world.getBlockAt(3, 65, 10); sign.setType(Material.OAK_SIGN);
        String key = ShopCommands.key(chest.getLocation());
        assertTrue(shops.create(owner.getUniqueId(), key, new ChunkPos("world", 0, 0), "diamond", 2, 10, 4, false, 0).ok());
        String signKey = ShopCommands.key(sign.getLocation());
        data.shops.get(key).sign = signKey;
        plugin.save();
        JsonStore store = new JsonStore(plugin.getDataFolder().toPath().resolve("data.json"));
        assertTrue(store.load().shops.containsKey(key));

        var breakChest = new BlockBreakEvent(chest, stranger);
        server.getPluginManager().callEvent(breakChest);
        assertTrue(breakChest.isCancelled());
        assertTrue(shops.at(key).isPresent());
        var breakSign = new BlockBreakEvent(sign, stranger);
        server.getPluginManager().callEvent(breakSign);
        assertTrue(breakSign.isCancelled());
        assertTrue(shops.at(key).isPresent());
        assertEquals(signKey, data.shops.get(key).sign);

        var ownerBreak = new BlockBreakEvent(chest, owner);
        server.getPluginManager().callEvent(ownerBreak);
        assertFalse(ownerBreak.isCancelled());
        assertTrue(shops.at(key).isEmpty());
        assertEquals(Material.AIR, sign.getType());
        // Before this fix, the shop reappears after a crash before the periodic save.
        assertFalse(store.load().shops.containsKey(key));
    }
}
