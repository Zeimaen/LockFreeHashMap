package extras.util.concurrent;

import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import extras.util.concurrent.LockFreeHashMap;

public class LockFreeHashMapConcurrencyTest {

    private Random rand = new Random();

    @Test
    public void CreateUpdateDeleteTest() {
        final int NUM_THREADS = 20;
        final int NUM_OPS_TOTAL = 1000000;
        final int NUM_KEYS = 1024;
        final Map<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(NUM_KEYS * 2, 0.8f, false);
        final Set<Integer> insertedKeys = new ConcurrentSkipListSet<Integer>();
        final Set<Integer> inUseKeys = new ConcurrentSkipListSet<Integer>();

        ExecutorService exec = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Integer>> results = new ArrayList<Future<Integer>>(NUM_OPS_TOTAL);

        for (int i = 0; i < NUM_OPS_TOTAL; ++i) {
            results.add(exec.submit(new Callable<Integer>() {

                @Override
                public Integer call() throws Exception {
                    int op = rand.nextInt(99) + 1;
                    int nextKey = getKey(inUseKeys, NUM_KEYS);
                    Integer res = null;

                    try {
                        if (op <= 15 || !insertedKeys.contains(nextKey)) {
                            res = map.put(nextKey, nextKey * 10);
                            insertedKeys.add(nextKey);
                            if (res == null)
                                res = 0;
                        } else if (op <= 30 && insertedKeys.remove(nextKey)) {
                            res = map.remove(nextKey);
                            if (res == null) {
                                throw new Exception("Deleted non existing item with key " + nextKey);
                            }
                        } else {
                            res = map.get(nextKey);
                            if (res == null || nextKey * 10 != res.intValue()) {
                                throw new Exception("Got value " + res + " for key " + nextKey);
                            }
                        }
                    } finally {
                        releaseKey(nextKey, inUseKeys);
                    }
                    return null;
                }

            }));
        }

        try {
            for (Future<Integer> r : results) {
                r.get();
            }
        } catch (Exception e) {
            fail();
        }
    }

    private int getKey(Set<Integer> inUseKeys, int NUM_KEYS) {
        int nextKey;
        do {
            nextKey = rand.nextInt(NUM_KEYS) + 1;
        } while (!inUseKeys.add(nextKey));
        return nextKey;
    }

    private void releaseKey(int key, Set<Integer> inUseKeys) {
        inUseKeys.remove(key);
    }

    @Test
    public void GetUpdateSameKeyTest() {
        final int NUM_THREADS = 10;
        final int NUM_OPS_PER_THREAD = 100000;
        final AtomicInteger value = new AtomicInteger(1);
        final Map<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>();

        ExecutorService exec = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Integer>> results = new ArrayList<Future<Integer>>(NUM_THREADS);

        for (int i = 0; i < NUM_THREADS; ++i) {
            results.add(exec.submit(new Callable<Integer>() {

                @Override
                public Integer call() throws Exception {
                    map.put(1, value.get());
                    for (int i = 0; i < NUM_OPS_PER_THREAD; ++i) {
                        Integer r1 = map.get(1);
                        int newValue = value.incrementAndGet();
                        map.put(1, newValue);
                        if (r1 == null) {
                            throw new Exception("Got value null for key 1");
                        } else if (r1 > newValue) {
                            throw new Exception("Should have r1 <= newValue but got " + r1 + " <= " + newValue);
                        }
                    }
                    return null;
                }

            }));
        }

        try {
            for (Future<Integer> r : results) {
                r.get();
            }
        } catch (Exception e) {
            fail();
        }
    }

    @Test
    public void ResizeGetTest() {
        final int NUM_THREADS = Runtime.getRuntime().availableProcessors() - 1;
        final int NUM_OPS_PER_THREAD = 100000;
        final LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>();

        int cnt = 1;
        while (map.nextResize() > 0) {
            map.put(cnt, cnt * 10);
            ++cnt;
        }

        final int lastInserted = cnt - 1;
        ExecutorService exec = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Integer>> results = new ArrayList<Future<Integer>>(NUM_THREADS);
        for (int i = 0; i < NUM_THREADS; ++i) {
            results.add(exec.submit(new Callable<Integer>() {

                @Override
                public Integer call() throws Exception {
                    try {
                        for (int i = 0; i < NUM_OPS_PER_THREAD; ++i) {
                            int nextKey = rand.nextInt(lastInserted) + 1;
                            Integer res = map.get(nextKey);
                            if (res == null || !res.equals(nextKey * 10)) {
                                throw new Exception("Got value " + res + " for key " + nextKey);
                            }
                        }
                    } catch (Exception e) {
                        for (StackTraceElement el : e.getStackTrace()) {
                            System.out.println(el);
                        }
                        throw new Exception();
                    }
                    return 1;
                }

            }));
        }

        // Do resize
        map.put(cnt, cnt * 10);

        try {
            for (Future<Integer> r : results) {
                r.get();
            }
        } catch (Exception e) {

            fail();
        }
    }

    @Test
    public void ResizeContainsTest() {
        final int NUM_THREADS = Runtime.getRuntime().availableProcessors() - 1;
        final int NUM_OPS_PER_THREAD = 100000;
        final LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>();

        int cnt = 1;
        while (map.nextResize() > 0) {
            map.put(cnt, cnt * 10);
            ++cnt;
        }

        final int lastInserted = cnt - 1;
        ExecutorService exec = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Integer>> results = new ArrayList<Future<Integer>>(NUM_THREADS);
        for (int i = 0; i < NUM_THREADS; ++i) {
            results.add(exec.submit(new Callable<Integer>() {

                @Override
                public Integer call() throws Exception {
                    try {
                        for (int i = 0; i < NUM_OPS_PER_THREAD; ++i) {
                            int nextKey = rand.nextInt(lastInserted) + 1;
                            if (!map.containsKey(nextKey)) {
                                throw new Exception("Map does not contain key " + nextKey);
                            }
                        }
                    } catch (Exception e) {
                        for (StackTraceElement el : e.getStackTrace()) {
                            System.out.println(el);
                        }
                        throw new Exception();
                    }
                    return null;
                }

            }));
        }

        // Do resize
        map.put(cnt, cnt * 10);

        try {
            for (Future<Integer> r : results) {
                r.get();
            }
        } catch (Exception e) {
            System.out.println(e);
            fail();
        }
    }
}