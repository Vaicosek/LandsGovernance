package dev.abex.gov.core;

import java.util.UUID;

/** A chest that trades one item. Prices are per trade of {@code qty} items; a negative price turns that side off. */
public final class Shop {
    /** world:x:y:z of the chest. */
    public String key;
    public String chunk;
    /** world:x:y:z of the sign showing it, or null. */
    public String sign;
    /** Who opened the shop; keeps the chest if a city shop's city is deleted. */
    public UUID owner;
    /** Set for a city shop: money goes in and out of this city's treasury. */
    public String city;
    public String item;
    public int qty;
    public double buy;
    public double sell;
    public long created;

    public boolean buys() { return sell >= 0; }
    public boolean sells() { return buy >= 0; }
}
