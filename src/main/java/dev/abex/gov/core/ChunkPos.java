package dev.abex.gov.core;

public record ChunkPos(String world, int x, int z) {
    public String key() { return world + ":" + x + ":" + z; }

    public static ChunkPos fromKey(String key) {
        int last = key.lastIndexOf(':');
        int before = key.lastIndexOf(':', last - 1);
        if (before < 0) throw new IllegalArgumentException("Invalid chunk key: " + key);
        return new ChunkPos(key.substring(0, before), Integer.parseInt(key.substring(before + 1, last)),
                Integer.parseInt(key.substring(last + 1)));
    }
}
