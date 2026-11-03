package dev.abex.gov.core;

import java.util.*;

public final class Bank {
    private final LandBook book;
    private final EconomyPort economy;

    public Bank(LandBook book, EconomyPort economy) { this.book = book; this.economy = economy; }

    public Result deposit(UUID actor, String name, double amount, long now) {
        if (economy == null) return Result.no("No economy plugin installed.");
        Land land = book.land(name).orElse(null);
        if (land == null || !land.members.containsKey(actor)) return Result.no("Only members can deposit.");
        if (!valid(amount) || !Double.isFinite(land.balance + amount)) return Result.no("Choose a positive finite amount.");
        if (economy.balance(actor) < amount || !economy.withdraw(actor, amount)) return Result.no("You couldn't pay that amount.");
        record(land, now, actor, amount, "Deposit");
        return Result.ok("Deposited " + amount + ".");
    }

    public Result withdraw(UUID actor, String name, double amount, long now) {
        if (economy == null) return Result.no("No economy plugin installed.");
        Land land = book.land(name).orElse(null);
        if (!book.has(land, actor, Perm.BANK)) return Result.no("You can't withdraw here.");
        if (!valid(amount) || amount > land.balance) return Result.no("Choose an amount the treasury can cover.");
        // The adapter must throw if a deposit fails; leave the bank untouched in that case.
        try { economy.deposit(actor, amount); }
        catch (RuntimeException e) { return Result.no("The economy couldn't accept the withdrawal."); }
        record(land, now, actor, -amount, "Withdrawal");
        return Result.ok("Withdrew " + amount + ".");
    }

    private static boolean valid(double amount) { return Double.isFinite(amount) && amount > 0; }

    public static void record(Land land, long now, UUID who, double amount, String reason) {
        land.balance += amount;
        land.ledger.add(new LedgerEntry(now, who, amount, reason));
        while (land.ledger.size() > 50) land.ledger.removeFirst();
    }

    public List<LedgerEntry> recent(Land land) {
        List<LedgerEntry> entries = new ArrayList<>(land.ledger.subList(Math.max(0, land.ledger.size() - 10), land.ledger.size()));
        Collections.reverse(entries);
        return List.copyOf(entries);
    }

    public static void record(Estate estate, long now, UUID who, double amount, String reason) {
        estate.balance += amount;
        estate.ledger.add(new LedgerEntry(now, who, amount, reason));
        while (estate.ledger.size() > 50) estate.ledger.removeFirst();
    }
    public static void record(Realm realm, long now, UUID who, double amount, String reason) {
        realm.balance += amount;
        realm.ledger.add(new LedgerEntry(now, who, amount, reason));
        while (realm.ledger.size() > 50) realm.ledger.removeFirst();
    }
    public Result estateDeposit(UUID actor, String name, double amount, long now) {
        Estate estate = book.estate(name).orElse(null);
        if (economy == null) return Result.no("No economy plugin installed.");
        if (estate == null || !valid(amount) || !Double.isFinite(estate.balance + amount)) return Result.no("Choose a positive finite amount and an estate.");
        if (economy.balance(actor) < amount || !economy.withdraw(actor, amount)) return Result.no("You cannot pay that amount.");
        record(estate, now, actor, amount, "Deposit"); return Result.ok("Deposited " + amount + ".");
    }
    public Result estateWithdraw(UUID actor, String name, double amount, long now) {
        Estate estate = book.estate(name).orElse(null);
        if (economy == null) return Result.no("No economy plugin installed.");
        if (estate == null || !estate.owner.equals(actor)) return Result.no("Only the estate owner can withdraw.");
        if (!valid(amount) || amount > estate.balance) return Result.no("Choose an amount the treasury can cover.");
        try { economy.deposit(actor, amount); } catch (RuntimeException failure) { return Result.no("Payout failed."); }
        record(estate, now, actor, -amount, "Withdrawal"); return Result.ok("Withdrew " + amount + ".");
    }
}
