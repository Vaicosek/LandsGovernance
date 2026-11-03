package dev.abex.gov.core;

import java.util.Set;

public record Settings(
        int tenureDays,
        int minPlaytimeHours,
        boolean oneVettedPerIp,
        int defaultElectionHours,
        int minVoters,
        int termDays,
        int charterRevokeDays,
        Set<String> votingRoles) {
}
