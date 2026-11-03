package dev.abex.gov.core;

import com.google.gson.GsonBuilder;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;

/** Immutable wire snapshot; inventory and name callbacks run on the server thread. */
public final class ShopWeb {
    private ShopWeb() {}
    public static String token() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public record Row(String shop, String item, String side, double price, Integer stock, String city, int qty) {}
    public record Trade(long time_ms, String item, int qty, double price, String side, String buyer) {}
    public record Snapshot(String token, String owner_uuid, String owner_name, String server,
                           long time_ms, List<Row> shops, List<Trade> sales) {
        public String body() {
            return new GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(this);
        }
    }
    public static Snapshot snapshot(ShopService service, UUID owner, String ownerName, String server,
                                    String token, long now, Function<Shop, Integer> stock,
                                    Function<UUID, String> names) {
        List<Row> rows = new ArrayList<>();
        service.of(owner).stream().sorted(Comparator.comparing(s -> s.key)).forEach(s -> {
            Integer count = stock.apply(s);
            // BUY/SELL use the same customer perspective as the sale feed.
            if (s.sells()) rows.add(new Row(s.key, s.item, "BUY", s.buy, count, s.city, s.qty));
            if (s.buys()) rows.add(new Row(s.key, s.item, "SELL", s.sell, count, s.city, s.qty));
        });
        List<Trade> sales = service.salesOf(owner).stream().limit(50)
                .map(s -> new Trade(s.time(), s.item(), s.qty(), s.price(), s.side().name(), names.apply(s.player())))
                .toList();
        return new Snapshot(token, owner.toString(), ownerName, server, now, List.copyOf(rows), sales);
    }
}
