package dev.abex.gov.core;

import java.util.*;

public final class ClaimMap {
    public enum Kind { YOU, OWN_LAND, OWN_PLOT, OTHER_LAND, WILD }
    public record Cell(ChunkPos chunk, Kind kind, String land, boolean claimable) {}
    private ClaimMap() {}

    public static List<List<Cell>> render(LandBook book, ChunkPos center, UUID viewer, int radius) {
        if (radius < 0 || radius > 32) throw new IllegalArgumentException("Map radius must be 0-32.");
        List<List<Cell>> rows = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            List<Cell> row = new ArrayList<>();
            for (int dx = -radius * 2; dx <= radius * 2; dx++) {
                ChunkPos chunk = new ChunkPos(center.world(), center.x() + dx, center.z() + dz);
                Land land = book.landAt(chunk).orElse(null);
                Kind kind = land == null ? Kind.WILD : viewer.equals(land.plots.get(chunk.key())) ? Kind.OWN_PLOT
                        : land.members.containsKey(viewer) ? Kind.OWN_LAND : Kind.OTHER_LAND;
                if (chunk.equals(center)) kind = Kind.YOU;
                row.add(new Cell(chunk, kind, land == null ? "Wilderness" : book.estateAt(chunk).map(e -> e.name + " (" + land.name + ")").orElse(land.name),
                        land == null && book.touches(viewer, chunk)));
            }
            rows.add(List.copyOf(row));
        }
        return List.copyOf(rows);
    }
}
