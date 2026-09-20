package ch.bissbert.mortar;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedLeasesTest {
    @Test void twoShotsAndDelayedEffectsDoNotReleaseEachOthersTicket() {
        List<String> released = new ArrayList<>();
        List<String> acquired = new ArrayList<>();
        SharedLeases<String> leases = new SharedLeases<>(key -> { acquired.add(key); return true; }, released::add);
        Set<String> first = new HashSet<>(), second = new HashSet<>(), effect = new HashSet<>();
        assertTrue(leases.replace(first, Set.of("shared")));
        assertTrue(leases.replace(second, Set.of("shared")));
        assertTrue(leases.replace(effect, Set.of("shared", "effect")));
        leases.releaseAll(first); leases.releaseAll(second);
        assertTrue(released.isEmpty()); assertEquals(2, acquired.size());
        leases.releaseAll(effect);
        assertEquals(Set.of("shared", "effect"), new HashSet<>(released)); assertEquals(0, leases.size());
        leases.releaseAll(effect); assertEquals(2, released.size());
    }

    @Test void unavailableTerrainRollsBackNewTicketsAndKeepsExistingOwnership() {
        Set<String> live = new HashSet<>();
        SharedLeases<String> leases = new SharedLeases<>(key -> !key.equals("unexplored") && live.add(key), live::remove);
        Set<String> held = new HashSet<>();
        leases.replace(held, Set.of("old"));
        LinkedHashSet<String> wanted = new LinkedHashSet<>(List.of("new", "unexplored"));
        assertFalse(leases.replace(held, wanted));
        assertEquals(Set.of("old"), live); assertEquals(Set.of("old"), held);
    }

    @Test void exceptionsRollBackPartiallyAcquiredChunks() {
        Set<String> live = new HashSet<>();
        SharedLeases<String> leases = new SharedLeases<>(key -> {
            if (key.equals("bad")) throw new IllegalStateException("World unloading");
            return live.add(key);
        }, live::remove);
        Set<String> held = new HashSet<>();
        assertThrows(IllegalStateException.class, () -> leases.replace(held, new LinkedHashSet<>(List.of("new", "bad"))));
        assertTrue(live.isEmpty()); assertTrue(held.isEmpty()); assertEquals(0, leases.size());
    }

    @Test void movingSegmentReleasesOnlyChunksNoLongerNeeded() {
        Set<String> live = new HashSet<>();
        SharedLeases<String> leases = new SharedLeases<>(live::add, live::remove);
        Set<String> held = new HashSet<>();
        leases.replace(held, Set.of("a", "b")); leases.replace(held, Set.of("b", "c"));
        assertEquals(Set.of("b", "c"), live);
        leases.clear(); assertTrue(live.isEmpty()); assertEquals(0, leases.size());
    }
}
