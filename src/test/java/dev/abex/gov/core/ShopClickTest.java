package dev.abex.gov.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopClickTest {
    @Test void accessAndBypassAlwaysOpenOtherwiseSneakingSelectsSell() {
        for (boolean access : new boolean[]{false, true})
            for (boolean bypass : new boolean[]{false, true})
                for (boolean sneaking : new boolean[]{false, true})
                    assertEquals(access || bypass ? ShopClick.Action.OPEN : sneaking ? ShopClick.Action.SELL : ShopClick.Action.BUY,
                            ShopClick.decide(access, bypass, sneaking));
    }
}
