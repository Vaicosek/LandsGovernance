package dev.abex.gov.core;

import java.util.UUID;
import java.util.function.BiConsumer;

public final class DayProcessor {
    private RealmService realms;
    public void setRealms(RealmService realms) { this.realms = realms; }
    private final LandBook book;
    private final EconomyPort economy;
    private final double upkeep;
    private final int upkeepGrace, taxGrace;
    private final BiConsumer<String, String> announce;

    public DayProcessor(LandBook book, EconomyPort economy, double upkeep, int upkeepGrace, int taxGrace,
                        BiConsumer<String, String> announce) {
        if (!Double.isFinite(upkeep) || upkeep < 0 || upkeepGrace < 0 || taxGrace < 1)
            throw new IllegalArgumentException("Invalid economy settings.");
        this.book = book;
        this.economy = economy;
        this.upkeep = upkeep;
        this.upkeepGrace = upkeepGrace;
        this.taxGrace = taxGrace;
        this.announce = announce;
    }

    public void runDay(long now) {
        if (economy == null) return;
        for (Land land : book.list()) {
            for (UUID member : land.members.keySet()) {
                if (member.equals(land.owner) || land.tax <= 0) continue;
                if (Double.isFinite(land.balance + land.tax) && economy.balance(member) >= land.tax
                        && economy.withdraw(member, land.tax)) {
                    Bank.record(land, now, member, land.tax, "Daily tax");
                    land.taxStrikes.remove(member);
                } else {
                    int strikes = land.taxStrikes.merge(member, 1, Integer::sum);
                    if (strikes >= taxGrace) announce.accept(land.name, "A member has " + strikes
                            + " unpaid tax days. Review /c; nobody was removed.");
                }
            }
            double due = land.chunks.size() * upkeep;
            if (land.balance >= due) {
                if (due > 0) Bank.record(land, now, null, -due, "Daily upkeep");
                land.upkeepStrikes = 0;
            } else {
                if (land.balance > 0) Bank.record(land, now, null, -land.balance, "Unpaid upkeep");
                land.upkeepStrikes++;
                announce.accept(land.name, "Upkeep unpaid: strike " + land.upkeepStrikes + ".");
                if (land.upkeepStrikes > upkeepGrace) book.unclaimNewest(land).ifPresent(chunk ->
                        announce.accept(land.name, "The newest non-spawn chunk was unclaimed for unpaid upkeep."));
            }
            if (land.balance < land.chunks.size() * upkeep)
                announce.accept(land.name, "The treasury won't cover tomorrow's upkeep. Use /c deposit <amount>.");
        }
        if (realms != null) realms.taxDay(now);
    }
}
