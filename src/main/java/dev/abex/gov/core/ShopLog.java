package dev.abex.gov.core;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Human-readable shop history in the server's local time zone. */
public final class ShopLog {
    private ShopLog() {}
    public static String location(String key) {
        String[] parts = key.split(":");
        int n = parts.length;
        return "Shop @ " + parts[n - 3] + "," + parts[n - 2] + "," + parts[n - 1];
    }
    public static String line(Sale sale, String playerName, long now, ZoneId zone) {
        var time = Instant.ofEpochMilli(sale.time()).atZone(zone);
        boolean today = time.toLocalDate().equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
        return time.format(DateTimeFormatter.ofPattern(today ? "HH:mm" : "dd.MM HH:mm", Locale.ROOT))
                + " · " + playerName + (sale.side() == Sale.Side.BUY ? " bought " : " sold ")
                + sale.qty() + " " + sale.item() + " for " + String.format(Locale.ROOT, "%.2f", sale.price())
                + " · " + location(sale.shopKey());
    }
}
