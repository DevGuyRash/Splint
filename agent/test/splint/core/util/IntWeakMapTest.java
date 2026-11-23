package splint.core.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * IntWeakMap against java.util.HashMap as the reference: random puts, conditional removes and
 * lookups must agree, including through resizes and backward-shift deletions in long probe
 * chains (sequential keys, like the ID counter, and colliding keys).
 */
public final class IntWeakMapTest {
    private static int failures;

    public static void main(String[] args) {
        matchesHashMapUnderRandomOperations();
        sequentialKeysLikeTheIdCounter();
        removeOnlyWhenMappedToThatValue();
        collectedValuesDropOut();
        if (failures > 0) {
            System.out.println(failures + " IntWeakMap check(s) FAILED");
            System.exit(1);
        }
        System.out.println("IntWeakMap: all checks passed");
    }

    static void matchesHashMapUnderRandomOperations() {
        Random rnd = new Random(42);
        IntWeakMap<Object> map = new IntWeakMap<>(16);
        Map<Integer, Object> ref = new HashMap<>();
        List<Object> keepAlive = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            int key = rnd.nextInt(5_000);
            switch (rnd.nextInt(3)) {
                case 0 -> {
                    Object v = new Object();
                    keepAlive.add(v);
                    map.put(key, v);
                    ref.put(key, v);
                }
                case 1 -> {
                    Object v = ref.get(key);
                    map.remove(key, v);
                    ref.remove(key);
                }
                default -> {
                    if (map.get(key) != ref.get(key)) {
                        check("lookup of " + key + " after " + i + " operations", false);
                        return;
                    }
                }
            }
        }
        for (int k = 0; k < 5_000; k++) {
            if (map.get(k) != ref.get(k)) {
                check("final contents for key " + k, false);
                return;
            }
        }
        check("size matches reference (" + map.size() + " vs " + ref.size() + ")", map.size() == ref.size());
    }

    static void sequentialKeysLikeTheIdCounter() {
        IntWeakMap<Object> map = new IntWeakMap<>(16);
        Object[] values = new Object[50_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Object();
            map.put(10_000 + i, values[i]);
        }
        boolean ok = true;
        for (int i = 0; i < values.length; i++) ok &= map.get(10_000 + i) == values[i];
        check("50000 sequential ids all found", ok);
        for (int i = 0; i < values.length; i += 2) map.remove(10_000 + i, values[i]);
        ok = true;
        for (int i = 0; i < values.length; i++) ok &= map.get(10_000 + i) == (i % 2 == 0 ? null : values[i]);
        check("every other id removed, the rest still found", ok);
    }

    static void removeOnlyWhenMappedToThatValue() {
        IntWeakMap<Object> map = new IntWeakMap<>(16);
        Object parent = new Object(), grown = new Object();
        map.put(7, parent);
        map.put(7, grown);              // growth: the next stage takes over the parent's id
        map.remove(7, parent);          // the parent is deleted afterwards
        check("id stays with the object that took it over", map.get(7) == grown);
    }

    static void collectedValuesDropOut() {
        IntWeakMap<Object> map = new IntWeakMap<>(16);
        for (int i = 0; i < 1_000; i++) map.put(i, new Object());   // nothing keeps these alive
        for (int i = 0; i < 20 && map.get(0) != null; i++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int alive = 0;
        for (int i = 0; i < 1_000; i++) if (map.get(i) != null) alive++;
        check("unreferenced animals do not keep their ids (" + alive + " left)", alive == 0);
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
