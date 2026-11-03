package dev.abex.gov.core;

import java.util.UUID;

public interface Guard {
    boolean blockKick(String land, UUID target, long now);
    boolean blockDelete(String land);
    boolean blockTransfer(String land);
    boolean plotsLocked(String land);

    Guard NONE = new Guard() {
        public boolean blockKick(String land, UUID target, long now) { return false; }
        public boolean blockDelete(String land) { return false; }
        public boolean blockTransfer(String land) { return false; }
        public boolean plotsLocked(String land) { return false; }
    };
}
