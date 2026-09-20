package ch.bissbert.mortar;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Plugin chunk tickets are per-plugin, so simultaneous flights must share ownership. Main thread only. */
final class SharedLeases<K> {
    private final Map<K, Integer> counts = new HashMap<>();
    private final Predicate<K> acquire;
    private final Consumer<K> release;

    SharedLeases(Predicate<K> acquire, Consumer<K> release) { this.acquire = acquire; this.release = release; }

    boolean retain(K key) {
        Integer count = counts.get(key);
        if (count == null && !acquire.test(key)) return false;
        counts.put(key, count == null ? 1 : count + 1);
        return true;
    }

    void release(K key) {
        Integer count = counts.get(key);
        if (count == null) return;
        if (count > 1) counts.put(key, count - 1);
        else { counts.remove(key); release.accept(key); }
    }

    /** Transactional: a failed update leaves the caller's old leases intact. */
    boolean replace(Set<K> held, Set<K> wanted) {
        Set<K> acquired = new HashSet<>();
        try {
            for (K key : wanted) {
                if (held.contains(key)) continue;
                if (!retain(key)) { acquired.forEach(this::release); return false; }
                acquired.add(key);
            }
        } catch (RuntimeException failure) {
            acquired.forEach(this::release);
            throw failure;
        }
        for (K old : Set.copyOf(held)) if (!wanted.contains(old)) release(old);
        held.clear(); held.addAll(wanted);
        return true;
    }

    void releaseAll(Set<K> held) { Set.copyOf(held).forEach(this::release); held.clear(); }
    void clear() { Set.copyOf(counts.keySet()).forEach(release); counts.clear(); }
    int size() { return counts.size(); }
}
