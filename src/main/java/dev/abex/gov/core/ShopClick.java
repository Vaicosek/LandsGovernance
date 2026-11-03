package dev.abex.gov.core;

/** Right-click routing, independent of Bukkit. */
public final class ShopClick {
    private ShopClick() {}
    public enum Action { OPEN, BUY, SELL }
    public static Action decide(boolean canAccess, boolean bypass, boolean sneaking) {
        return canAccess || bypass ? Action.OPEN : sneaking ? Action.SELL : Action.BUY;
    }
}
