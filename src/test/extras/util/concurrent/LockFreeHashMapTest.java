package extras.util.concurrent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import extras.util.concurrent.LockFreeHashMap;

public class LockFreeHashMapTest {

    //Array of values that will be put in the same bucket for map size 16
    private int[] SAME_BUCKET_VALUES = {1,10,14,21,27};
    
    @Test
    public void testInit() {
        //Test default capacity
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>();
        int threshold = (int)(LockFreeHashMap.DEFAULT_INITIAL_CAPACITY * LockFreeHashMap.DEFAULT_LOAD_FACTOR);
        assertEquals(threshold, map.resizeThreshold);
        assertEquals(LockFreeHashMap.DEFAULT_INITIAL_CAPACITY, map.data.length);
        assertEquals(LockFreeHashMap.DEFAULT_LOAD_FACTOR, map.loadFactor, 0);
        assertEquals(0, map.size);
        
        
        //Test custom capacity and load factor
        map = new LockFreeHashMap<Integer, Integer>(40, 0.8f, false);
        assertEquals((int)(64 * 0.8f), map.resizeThreshold);
        assertEquals(64, map.data.length);
        assertEquals(0.8f, map.loadFactor, 0);
        assertEquals(0, map.size);
        
        //Test minimal capacity enforcement
        map = new LockFreeHashMap<Integer, Integer>(5, 0.5f, false);
        assertEquals((int)(16 * 0.5f), map.resizeThreshold);
        assertEquals(16, map.data.length);
        
        //Test minimal load factor enforcement
        map = new LockFreeHashMap<Integer, Integer>(16, 0.3f, false);
        assertEquals(LockFreeHashMap.MINIMAL_LOAD_FACTOR, map.loadFactor, 0);
        assertEquals((int)(16 * LockFreeHashMap.MINIMAL_LOAD_FACTOR), map.resizeThreshold);
    }
    
    @Test
    public void testTypes() {
        LockFreeHashMap<Integer, Integer> map1 = new LockFreeHashMap<Integer, Integer>();
        for(int v : SAME_BUCKET_VALUES) {
            map1.put(v, v+1);
            assertEquals(v+1, map1.get(v).intValue());
        }
        
        LockFreeHashMap<Long, String> map2 = new LockFreeHashMap<Long, String>();
        for(int v : SAME_BUCKET_VALUES) {
            map2.put((long)v, Integer.toString(v));
            assertEquals(Integer.toString(v), map2.get((long)v));
        }
        
        LockFreeHashMap<String, String> map3 = new LockFreeHashMap<String, String>();
        for(int v : SAME_BUCKET_VALUES) {
            map3.put(Integer.toString(v), Integer.toString(v+1));
            assertEquals(Integer.toString(v+1), map3.get(Integer.toString(v)));
        }
        
        LockFreeHashMap<Integer, String[]> map4 = new LockFreeHashMap<Integer, String[]>();
        String[] strArr = new String[SAME_BUCKET_VALUES.length];
        for(int i=0; i<SAME_BUCKET_VALUES.length;++i) {
            strArr[i] = Integer.toString(SAME_BUCKET_VALUES[i]);
        }
        for(int v : SAME_BUCKET_VALUES) {
            map4.put(v, strArr);
            Assert.assertArrayEquals(strArr, map4.get(v));
        }
                
        LockFreeHashMap<Integer, TestObject> map5 = new LockFreeHashMap<Integer, TestObject>();
        for(int v : SAME_BUCKET_VALUES) {
            map5.put(v, new TestObject(v+1,v+2));
            assertNotNull(map5.get(v));
            assertEquals(v+1, map5.get(v).test1);
            assertEquals(v+2, map5.get(v).test2);
        }
    }

    @Test
    public void testPutGet() {
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(16, 0.8f, false);
        
        //Test null insert
        assertNull(map.put(null, null));
        assertNull(map.put(0, null));
        assertNull(map.put(null, 0));
        
        //Test chaining
        for(int i = 0; i < 100; i++) {
            assertNull(map.get(i));
            map.put(i, i * 10);
            assertEquals(i * 10, map.get(i).intValue());
        }
        
        for(int i = 0; i < 100; i++) {
            assertEquals(i * 10, map.get(i).intValue());
        }
        
        //Test null get
        assertNull(map.get(100));
    }
    
    @Test
    public void testPutAll() {
        //TODO
    }
    
    @Test
    public void testPutIfAbsent() {
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(16, 0.8f, false);
        
        assertNull(map.putIfAbsent(null, null));
        assertNull(map.putIfAbsent(0, null));
        assertNull(map.putIfAbsent(null, 0));
        
        int key = SAME_BUCKET_VALUES[0];
        assertNull(map.putIfAbsent(key, key*10));
        assertEquals(key*10, map.get(key).intValue());
        assertEquals(key*10, map.putIfAbsent(key, key*20).intValue());
        assertEquals(key*10, map.get(key).intValue());
        
        map.remove(key);
        assertNull(map.putIfAbsent(key, key*20));
        assertEquals(key*20, map.get(key).intValue());
        
        key = SAME_BUCKET_VALUES[1];
        assertNull(map.putIfAbsent(key, key*10));
        assertEquals(key*10, map.get(key).intValue());
        assertEquals(key*10, map.putIfAbsent(key, key*20).intValue());
        assertEquals(key*10, map.get(key).intValue());
    }

    @Test
    public void testKeyDelete() {
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(16, 0.8f, false);
        
        //The remove of non existing value
        int test_key = 5;
        assertNull(map.remove(test_key));
        assertNull(map.remove(null));
        
        // Write value, delete it and write it again
        map.put(test_key, test_key*10);
        assertEquals(test_key*10, map.get(test_key).intValue());
        assertEquals(test_key*10, map.remove(test_key).intValue());
        assertNull(map.remove(test_key));
        map.put(test_key, test_key*20);
        assertEquals(test_key*20, map.get(test_key).intValue());

        // Add some values to the same bucket
        for(int val : SAME_BUCKET_VALUES) {
            map.put(val, val * 10);    
        }
        
        //delete a value in the  middle of the chain
        test_key = SAME_BUCKET_VALUES[2];
        assertEquals(test_key*10, map.get(test_key).intValue());
        assertEquals(test_key*10, map.remove(test_key).intValue());
        assertNull(map.remove(test_key));
        assertEquals(SAME_BUCKET_VALUES[3]*10, map.get(SAME_BUCKET_VALUES[3]).intValue());
        
        //delete at the beginning of the chain
        test_key = SAME_BUCKET_VALUES[0];
        assertEquals(test_key*10, map.get(test_key).intValue());
        assertEquals(test_key*10, map.remove(test_key).intValue());
        assertNull(map.remove(test_key));
        assertEquals(SAME_BUCKET_VALUES[3]*10, map.get(SAME_BUCKET_VALUES[3]).intValue());
        
        //delete at the end of the chain
        test_key = SAME_BUCKET_VALUES[4];
        assertEquals(test_key*10, map.get(test_key).intValue());
        assertEquals(test_key*10, map.remove(test_key).intValue());
        assertNull(map.remove(test_key));
        assertEquals(SAME_BUCKET_VALUES[3]*10, map.get(SAME_BUCKET_VALUES[3]).intValue());
    }
    
    @Test
    public void testKeyValueDelete() {
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(16, 0.8f, false);
        
        //The remove of non existing value
        int test_key = 5;
        assertFalse(map.remove(null, null));
        assertFalse(map.remove(test_key, null));
        assertFalse(map.remove(null, test_key));
        
        // Add some values to the same bucket
        for(int val : SAME_BUCKET_VALUES) {
            map.put(val, val * 10);    
        }
        
        //Try to delete with the wrong value
        test_key = SAME_BUCKET_VALUES[2];
        assertEquals(test_key*10, map.get(test_key).intValue());
        assertFalse(map.remove(test_key, test_key));
        
        //Try to delete with the right value
        assertEquals(test_key*10, map.get(test_key).intValue());
        assertTrue(map.remove(test_key, test_key*10));
        assertNull(map.remove(test_key));
        assertEquals(SAME_BUCKET_VALUES[1]*10, map.get(SAME_BUCKET_VALUES[1]).intValue());
        assertEquals(SAME_BUCKET_VALUES[3]*10, map.get(SAME_BUCKET_VALUES[3]).intValue());
        
        //Insert again
        map.put(test_key, test_key*5);
        assertEquals(test_key*5, map.get(test_key).intValue());
    }
    
    @Test
    public void testSize() {
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(16, 0.8f, false);
        
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
        
        for(int val : SAME_BUCKET_VALUES) {
            map.put(val, val * 10);    
        }
        
        assertFalse(map.isEmpty());
        
        assertEquals(SAME_BUCKET_VALUES.length, map.size());
        map.remove(SAME_BUCKET_VALUES[1]); //Remove -> decrement size
        assertEquals(SAME_BUCKET_VALUES.length-1, map.size());
        map.remove(SAME_BUCKET_VALUES[3], SAME_BUCKET_VALUES[3] * 10); //Remove -> decrement size
        assertEquals(SAME_BUCKET_VALUES.length-2, map.size());
        map.putIfAbsent(SAME_BUCKET_VALUES[2], 0); //Value exists -> should not affect size
        assertEquals(SAME_BUCKET_VALUES.length-2, map.size());
        map.putIfAbsent(SAME_BUCKET_VALUES[1], SAME_BUCKET_VALUES[1]*10); // Value does not exists -> increment size
        assertEquals(SAME_BUCKET_VALUES.length-1, map.size());
        map.replace(SAME_BUCKET_VALUES[1], 0); //Value exists -> should not affect size
        assertEquals(SAME_BUCKET_VALUES.length-1, map.size());
        map.replace(SAME_BUCKET_VALUES[3], 0); //Value does not exists -> should not affect size
        assertEquals(SAME_BUCKET_VALUES.length-1, map.size());
        
        for(int i = 0; i < 100; ++i) {
            map.put(i, i * 10);
        }
        assertEquals(100, map.size());
        
        for(int i = 0; i < 100; i = i+2) {
            map.remove(i);
        }
        assertEquals(50, map.size());
        
        map.clear();
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
    }

    @Test
    public void testContains() {
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(16, 0.8f, false);
        List<Integer> values = getValueList();
        for(int v : values)
            map.put(v, v*10);
        
        assertTrue(map.containsKey(values.get(0)));
        assertTrue(map.containsKey(values.get(3)));
        assertTrue(map.containsKey(values.get(7)));
        assertFalse(map.containsKey(Integer.MAX_VALUE));
        
        assertTrue(map.containsValue(values.get(0)*10));
        assertTrue(map.containsValue(values.get(3)*10));
        assertTrue(map.containsValue(values.get(7)*10));
        assertFalse(map.containsValue(Integer.MAX_VALUE));
    }
    
    @Test
    public void testReplace() {
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(16, 0.8f, false);
        
        assertNull(map.replace(null, null));
        assertNull(map.replace(0, null));
        assertNull(map.replace(null, 0));
        
        int key = SAME_BUCKET_VALUES[0];
        assertNull(map.replace(key, key*10));
        assertNull(map.get(key));
        
        // Add some values to the same bucket
        for(int val : SAME_BUCKET_VALUES) {
            map.put(val, val * 10);    
        }
        
        assertEquals(key*10, map.replace(key, key*20).intValue());
        assertEquals(key*20, map.get(key).intValue());
        map.remove(key);
        assertNull(map.replace(key, key*10));
        
        key = SAME_BUCKET_VALUES[3];
        assertEquals(key*10, map.replace(key, key*20).intValue());
        assertEquals(key*20, map.get(key).intValue());
        map.remove(key);
        assertNull(map.replace(key, key*10));
        
        //Replace(K, V, V)
        assertFalse(map.replace(null, null, null));
        assertFalse(map.replace(0, null, null));
        assertFalse(map.replace(null, 0, null));
        assertFalse(map.replace(null, null, 0));
        
        assertFalse(map.replace(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertNull(map.get(Integer.MAX_VALUE));
        
        key = SAME_BUCKET_VALUES[2];
        assertFalse(map.replace(key, key*20, key * 30)); //Wrong old value
        assertEquals(key*10, map.get(key).intValue());
        assertTrue(map.replace(key, key*10, key * 30)); //Correct old value
        assertEquals(key*30, map.get(key).intValue());
        map.remove(key);
        assertFalse(map.replace(key, key*30, key*50)); //Key does not exists anymore
    }
    
    @Test
    public void testIteratorSets() {
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(16, 0.8f, false);
        List<Integer> values = getValueList();
        for(int v : values)
            map.put(v, v*10);
                
        //KeySet
        Set<Integer> keyset = map.keySet();
        assertTrue(keyset.contains(values.get(0)));
        assertFalse(keyset.contains(Integer.MAX_VALUE));        
        assertFalse(keyset.isEmpty());
        assertEquals(keyset.size(), values.size());
               
        Iterator<Integer> it = keyset.iterator();
        int cnt = 0;
        while(it.hasNext()) {
            int n = it.next();
            ++cnt;
            assertTrue(values.contains(n));
        }
        assertEquals(cnt, values.size());
        
        keyset.remove(Integer.MAX_VALUE);
        assertEquals(keyset.size(), values.size());
        
        keyset.remove(values.get(0));
        assertFalse(keyset.contains(values.get(0)));        
        assertEquals(keyset.size(), values.size()-1);
                
        keyset.clear();
        assertTrue(keyset.isEmpty());
        assertEquals(0, keyset.size());

        for(int v : values)
            map.put(v, v*10);
        
        //ValueSet
        Collection<Integer> valset = map.values();
        assertTrue(valset.contains(values.get(0)*10));
        assertFalse(valset.contains(Integer.MAX_VALUE));        
        assertFalse(valset.isEmpty());
        assertEquals(valset.size(), values.size());
               
        it = valset.iterator();
        cnt = 0;
        while(it.hasNext()) {
            int n = it.next();
            ++cnt;
            assertTrue(values.contains(n/10));
        }
        assertEquals(cnt, values.size());
        
        valset.remove(Integer.MAX_VALUE);
        assertEquals(valset.size(), values.size());
        
        valset.remove(values.get(0)*10);
        assertFalse(valset.contains(values.get(0)*10));        
        assertEquals(valset.size(), values.size()-1);
                
        valset.clear();
        assertTrue(valset.isEmpty());
        assertEquals(0, valset.size());

        for(int v : values)
            map.put(v, v*10);
        
        //EntrySet 
        Set<Entry<Integer, Integer>> entryset = map.entrySet();
        assertTrue(entryset.contains(new AbstractMap.SimpleEntry<Integer,Integer>(values.get(0), values.get(0)*10)));
        assertFalse(entryset.contains(new AbstractMap.SimpleEntry<Integer,Integer>(Integer.MAX_VALUE, values.get(0)*10)));
        assertFalse(entryset.contains(new AbstractMap.SimpleEntry<Integer,Integer>(values.get(0), values.get(0)*9)));
        assertFalse(entryset.isEmpty());
        assertEquals(entryset.size(), values.size());
               
        Iterator<Entry<Integer,Integer>> it2 = entryset.iterator();
        cnt = 0;
        while(it2.hasNext()) {
            Entry<Integer,Integer> n = it2.next();
            ++cnt;
            assertEquals(n.getKey().intValue(), n.getValue()/10);
            assertTrue(values.contains(n.getKey()));
        }
        assertEquals(cnt, values.size());
        
        entryset.remove(new AbstractMap.SimpleEntry<Integer,Integer>(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(entryset.size(), values.size());
        
        entryset.remove(new AbstractMap.SimpleEntry<Integer,Integer>(values.get(0), values.get(0)*10));
        assertFalse(entryset.contains(new AbstractMap.SimpleEntry<Integer,Integer>(values.get(0), values.get(0)*10)));        
        assertEquals(entryset.size(), values.size()-1);
                
        entryset.clear();
        assertTrue(entryset.isEmpty());
        assertEquals(0, entryset.size());
    }
    
    @Test
    public void testResize() {
        LockFreeHashMap<Integer, Integer> map = new LockFreeHashMap<Integer, Integer>(16, 0.8f, true);
        
        assertEquals(12, map.resizeThreshold);
        
        int count = 1;
        while(map.nextResize() > 0) {
            map.put(count, count * 10);
            ++count;
        }
        assertEquals(12, map.size());
        
        map.put(count, count * 10);
        ++count;
        
        assertEquals(25, map.resizeThreshold);
        assertEquals(13, map.size());
        assertEquals(12, map.nextResize());
        
        for(int i = 1; i < count; ++i) {
            assertEquals(i*10, map.get(i).intValue());
        }        
    }
    
    private List<Integer> getValueList() {
        List<Integer> values = new ArrayList<Integer>(10);
        for(int val : SAME_BUCKET_VALUES) 
            values.add(val);
        for(int i = 6; i <= 30; i += 6) 
            values.add(i);
        return values;        
    }
    
    public class TestObject {
        int test1;
        int test2;
        
        public TestObject(int test1, int test2) {
            this.test1 = test1;
            this.test2 = test2;                   
        }        
    }
}