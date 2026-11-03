package dev.abex.gov.core;

import java.util.UUID;

/** Immutable trade snapshot. BUY is income; SELL is expense for the shop. */
public record Sale(long id, long time, String shopKey, UUID owner, String city,
                   UUID player, Side side, String item, int qty, double price) {
    public enum Side { BUY, SELL }
}
