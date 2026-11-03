package dev.abex.gov.bukkit;

import dev.abex.gov.core.EconomyPort;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.util.UUID;

final class VaultEconomy implements EconomyPort {
    private final Economy economy;
    private VaultEconomy(Economy economy) { this.economy = economy; }

    static EconomyPort find() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : new VaultEconomy(provider.getProvider());
    }

    public double balance(UUID who) { return economy.getBalance(Bukkit.getOfflinePlayer(who)); }
    public boolean withdraw(UUID who, double amount) {
        return economy.withdrawPlayer(Bukkit.getOfflinePlayer(who), amount).transactionSuccess();
    }
    public void deposit(UUID who, double amount) {
        if (!economy.depositPlayer(Bukkit.getOfflinePlayer(who), amount).transactionSuccess())
            throw new IllegalStateException("Economy deposit failed.");
    }
}
