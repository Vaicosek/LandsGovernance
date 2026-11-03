package dev.abex.gov.bukkit;

import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopWebLinkTest {
    @Test void firstWebUseRecordsTheOneTimeTutorial() throws Exception {
        var server = org.mockbukkit.mockbukkit.MockBukkit.mock();
        try {
            var player = server.addPlayer();
            GovPlugin plugin = org.mockbukkit.mockbukkit.MockBukkit.load(GovPlugin.class);
            var field = GovPlugin.class.getDeclaredField("data");
            field.setAccessible(true);
            var data = (dev.abex.gov.core.GovData) field.get(plugin);
            String key = player.getUniqueId() + "|shop-web";
            assertFalse(data.tipsSeen.contains(key));
            assertTrue(plugin.shopCommands.command(player, "shop", new String[]{"shop", "web"}));
            assertTrue(data.tipsSeen.contains(key));
            int count = data.tipsSeen.size();
            plugin.shopCommands.command(player, "shop", new String[]{"shop", "web"});
            assertEquals(count, data.tipsSeen.size());
        } finally { org.mockbukkit.mockbukkit.MockBukkit.unmock(); }
    }

    @Test void linkOpensThePrivatePageWithoutTypingAnId() {
        var link = ShopCommands.webLink("https://restocker.example/base/", "abc_DEF-123");
        assertEquals("Open my shops (expires in 24h)", ((TextComponent) link).content());
        assertNotNull(link.clickEvent());
        assertEquals(ClickEvent.Action.OPEN_URL, link.clickEvent().action());
        assertEquals("https://restocker.example/base/shops/abc_DEF-123", ((net.kyori.adventure.text.event.ClickEvent.Payload.Text) link.clickEvent().payload()).value());
    }
}
