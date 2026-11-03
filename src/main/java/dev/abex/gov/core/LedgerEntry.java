package dev.abex.gov.core;

import java.util.UUID;

public record LedgerEntry(long time, UUID who, double amount, String reason) {}
