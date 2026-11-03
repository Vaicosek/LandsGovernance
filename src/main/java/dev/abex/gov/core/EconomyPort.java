package dev.abex.gov.core;

import java.util.UUID;

public interface EconomyPort {
    double balance(UUID who);
    boolean withdraw(UUID who, double amount);
    void deposit(UUID who, double amount);
}
