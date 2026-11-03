package dev.abex.gov.core;

public record Spot(String world, double x, double y, double z, float yaw, float pitch) {
    public ChunkPos chunk() { return new ChunkPos(world, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4); }
}
