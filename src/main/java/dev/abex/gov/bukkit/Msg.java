package dev.abex.gov.bukkit;

import dev.abex.gov.core.Result;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

final class Msg {
    private static final Component PREFIX = Component.text("Cities ", NamedTextColor.GOLD);

    private Msg() {
    }

    static Component info(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.GRAY));
    }

    /** Set during /c admin as: results are also shown to the staff member who ran it. */
    static CommandSender echo;

    static Component of(Result r) {
        Component c = PREFIX.append(Component.text(r.message(), r.ok() ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (echo != null) echo.sendMessage(Component.text(r.ok() ? "  ok: " : "  NO: ").append(c));
        return c;
    }

    /** A clickable chat button that runs a command. */
    static Component button(String label, String command, String hover) {
        return Component.text("[" + label + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }
}
