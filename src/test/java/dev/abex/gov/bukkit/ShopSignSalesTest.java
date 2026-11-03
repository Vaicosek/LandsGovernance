package dev.abex.gov.bukkit;

import dev.abex.gov.core.*;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopSignSalesTest {
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void rightClickSignPaysOwnerMovesItemsAndRecordsSale() throws Exception {
        var server = MockBukkit.mock();
        var world = server.addSimpleWorld("world");
        var owner = server.addPlayer();
        var buyer = server.addPlayer();
        GovPlugin plugin = MockBukkit.load(GovPlugin.class);
        // Reuse the plugin's persisted data, but supply a deterministic economy instead of Vault.
        var field = GovPlugin.class.getDeclaredField("data");
        field.setAccessible(true);
        GovData data = (GovData) field.get(plugin);
        LandBook book = new LandBook(data, GovPlugin.roles(plugin.getConfig()), new ClaimSettings(3, 0));
        Map<UUID, Double> balances = new HashMap<>();
        balances.put(buyer.getUniqueId(), 100.0);
        EconomyPort economy = new EconomyPort() {
            public double balance(UUID id) { return balances.getOrDefault(id, 0.0); }
            public boolean withdraw(UUID id, double amount) {
                if (balance(id) < amount) return false;
                balances.put(id, balance(id) - amount); return true;
            }
            public void deposit(UUID id, double amount) { balances.put(id, balance(id) + amount); }
        };
        ShopService shops = new ShopService(data, book, economy);
        HandlerList.unregisterAll(plugin.shopCommands);
        plugin.shopCommands = new ShopCommands(plugin, shops, data);
        server.getPluginManager().registerEvents(plugin.shopCommands, plugin);
        var chest = world.getBlockAt(0, 64, 0); chest.setType(Material.CHEST);
        var sign = world.getBlockAt(0, 65, 0); sign.setType(Material.OAK_SIGN);
        String key = ShopCommands.key(chest.getLocation());
        assertTrue(shops.create(owner.getUniqueId(), key, new ChunkPos("world", 0, 0), "diamond", 2, 10, 4, false, 0).ok());
        data.shops.get(key).sign = ShopCommands.key(sign.getLocation());
        // MockBukkit doesn't keep a chest's contents between getState() calls, so this checks the sneak-click
        // sell side, which takes from the player's inventory and only needs room in the (empty) chest.
        balances.put(owner.getUniqueId(), 100.0);
        buyer.getInventory().addItem(new ItemStack(Material.DIAMOND, 2));
        buyer.setSneaking(true);
        var event = new PlayerInteractEvent(buyer, Action.RIGHT_CLICK_BLOCK, null, sign, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "a non-owner's click never opens the shop");
        assertEquals(104, economy.balance(buyer.getUniqueId()));
        assertEquals(96, economy.balance(owner.getUniqueId()));
        assertEquals(0, Arrays.stream(buyer.getInventory().getStorageContents()).filter(Objects::nonNull)
                .filter(item -> item.getType() == Material.DIAMOND).mapToInt(ItemStack::getAmount).sum());
        assertEquals(1, data.sales.size());
        assertEquals(Sale.Side.SELL, data.sales.getFirst().side());
        assertEquals(buyer.getUniqueId(), data.sales.getFirst().player());
    }
}
