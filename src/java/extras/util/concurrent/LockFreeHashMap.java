package extras.util.concurrent;

import java.lang.reflect.Field;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

/**
 * A lock free concurrent hash-map implementation with dynamic resizing. 
 * 
 * By default the hash-map is initialized with a capacity of 128 and a resize factor of 0.65. 
 * This means, once the size of the map reaches 65% of it's capacity, the map is dynamically 
 * resized. Resizing is completely transparent. Custom initial capacity and resize factor 
 * can be specified in the constructor.
 * 
 * This object is thread-safe and can be accessed by multiple threads concurrently.
 *   
 * Compared to {@link java.util.concurrent.ConcurrentHashMap} this object does not 
 * use any locks and is likely to perform much better on computers of a high number 
 * of CPU cores. 
 *  
 * @author sloesing
 * 
 * @param <K> Key object
 * @param <V> Value object
 */
public class LockFreeHashMap<K, V> implements ConcurrentMap<K, V>
{
    static final int MINIMAL_CAPACITY = 16;
    static final float MINIMAL_LOAD_FACTOR = 0.5f;
    static final int DEFAULT_INITIAL_CAPACITY = 128;
    static final float DEFAULT_LOAD_FACTOR = 0.65f;

    int initialCapacity;
    float loadFactor;
    boolean isResizable;
    volatile int resizeThreshold;
    volatile int resizeLock;
    volatile HashEntry<K, V>[] data;
    volatile HashEntry<K, V>[] data_new;
    volatile int size;

    private transient Set<K> keySet;
    private transient Set<Entry<K, V>> entrySet;
    private transient Collection<V> values;

    /**
     * Creates a new hash-map with default capacity and resize factor
     */
    public LockFreeHashMap() {
        this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, true);
    }

    /**
     * Create a new hash-map with the specified constructor parameters 
     * 
     * @param initialCapacity 
     * @param isResizable false to deactivate resizing
     */
    public LockFreeHashMap(int initialCapacity, boolean isResizable) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR, isResizable);
    }

    /**
     * Create a new hash-map with the specified constructor parameters
     * 
     * @param initialCapacity
     * @param loadFactor resize factor - value between 0.5 and 1.0
     * @param isResizable false to deactivate resizing
     */
    @SuppressWarnings("unchecked")
    public LockFreeHashMap(int initialCapacity, float loadFactor, boolean isResizable) {
        // Find next power-of-two of the initial capacity
        this.initialCapacity = MINIMAL_CAPACITY;
        while (this.initialCapacity < initialCapacity) {
            this.initialCapacity <<= 1;
        }
        if (loadFactor >= MINIMAL_LOAD_FACTOR && loadFactor <= 1.0f) {
            this.loadFactor = loadFactor;
        } else {
            this.loadFactor = MINIMAL_LOAD_FACTOR;
        }
        this.resizeThreshold = (int) (this.initialCapacity * this.loadFactor);
        this.resizeLock = 0;
        this.isResizable = isResizable;
        this.data = (HashEntry<K, V>[]) new HashEntry[this.initialCapacity];
        this.data_new = null;
        this.size = 0;
    }

    /**
     * Clears the entire map and resets the capacity to the initial value
     */
    @SuppressWarnings("unchecked")
    @Override
    public void clear() {
        data = (HashEntry<K, V>[]) new HashEntry[this.initialCapacity];
        decSize(UNSAFE.getIntVolatile(this, SIZE_OFFSET));
    }


    /**
     * Verifies if a key is already present in the map
     * 
     * @param k key
     * @return true if key is contained in the map, otherwise false
     */
    @Override
    public boolean containsKey(Object k) {
        return get(k) != null;
    }

    /**
     * Verifies if a value is already present in the map
     * 
     * @param v value
     * @return true if the value is present, otherwise false 
     */
    @SuppressWarnings("unchecked")
    @Override
    public boolean containsValue(Object v) {
        V value = (V) v;
        for (int i = 0; i < data.length; ++i) {
            // Get bucket
            HashEntry<K, V> e = (HashEntry<K, V>) UNSAFE.getObjectVolatile(data, V_BASE + (V_SIZE * i));
            // Iterate until element is found or not
            while (e != null) {
                if (!e.isDeleted() && (e.value == value || value.equals(e.value))) {
                    return true;
                }
                e = e.getNext();
            }
        }
        return false;
    }

    /**
     * Retrieves the key-set of the map. 
     * The key set points to the current content of the map. Changes in the map are immediately visible in the set. 
     * As well, all operations executed on the set are directly applied to the map.
     *   
     * @return Set with all keys
     */
    @Override
    public Set<K> keySet() {
        Set<K> ks = keySet;
        return (ks != null) ? ks : (keySet = new KeySet());

    }

    /**
     * Retrieves all the values of the map. 
     * The collection points to the current content of the map. Changes in the map are immediately visible. 
     * As well, all operations executed on the coolection are directly applied to the map.
     *  
     * @return Collection with all values
     */
    @Override
    public Collection<V> values() {
        Collection<V> vs = values;
        return (vs != null) ? vs : (values = new Values());

    }

    /**
     * Retrieves all key/value pairs of the map. 
     * The entry set points to the current content of the map. Changes in the map are immediately visible in the set. 
     * As well, all operations executed on the set are directly applied to the map.
     *  
     * @return Collection with all values
     */
    public Set<Entry<K, V>> entrySet() {
        Set<Entry<K, V>> es = entrySet;
        return (es != null) ? es : (entrySet = new EntrySet());
    }

    /**
     * Retrieves a value from the map
     * 
     * @param key
     * @return V value if key exists, otherwise null
     */
    @SuppressWarnings("unchecked")
    @Override
    public final V get(Object key) {
        // Calculate hash
        int hash = hash(key.hashCode());
        // If we are resizing, first check new array
        boolean resizing = isResizing();
        HashEntry<K, V>[] dataArr = (resizing) ? data_new : data;

        for (int i = 0; i < 3; ++i) {
            // Get bucket
            HashEntry<K, V> e = (HashEntry<K, V>) UNSAFE.getObjectVolatile(dataArr, V_BASE + (V_SIZE * (hash % dataArr.length)));
            // Iterate until element is found or not
            if (e != null) {
                if (!e.isDeleted() && (e.key == key || (e.hash == hash && key.equals(e.key)))) {
                    return e.value;
                }

                e = e.getNext();
                while (e != null) {
                    if (!e.isDeleted() && (e.key == key || (e.hash == hash && key.equals(e.key)))) {
                        return e.value;
                    }
                    e = e.getNext();
                }
            }

            if (!resizing) {
                return null;
            } else if (i == 0) {
                dataArr = data;
            } else if (i == 1) {
                dataArr = (data_new != null) ? data_new : data;
                resizing = false;
            }
        }
        return null;
    }

    /**
     * Verifies if the map is empty (does not contain any keys)
     * 
     * @return true if map is empty
     */
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Inserts a key/value pair in the map. If the key is already present, the value is overwritten
     * 
     * @param key
     * @param value
     * @return V old value if key was already present in the map, null otherwise
     */
    @Override
    public V put(K key, V value) {
        if (key == null || value == null)
            return null;

        return put(key, value, 0, false, false, null, false);
    }

    /**
     * Inserts a key/value pair in the map only if the key does not exist yet.
     * 
     * @param key
     * @param value
     * @return V current value if the key is already present, null otherwise 
     */
    @Override
    public V putIfAbsent(K key, V value) {
        if (key == null || value == null)
            return null;

        return put(key, value, 0, true, false, null, false);
    }

    /**
     * Inserts all the entries of an existing map. This operation is not atomic. 
     * All entries are inserted sequentially
     * 
     * @param map to insert 
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Private generic put method used for all insert operations
     * 
     * @param key
     * @param value
     * @param hash value of the key
     * @param onlyIfAbsent boolean
     * @param onlyReplace boolean  
     * @param oldValue only used for replace
     * @param isResize internal flag to specify that the put is part of a resize operation
     * @return
     */
    @SuppressWarnings("unchecked")
    private final V put(K key, V value, int hash, boolean onlyIfAbsent, boolean onlyReplace, V oldValue, boolean isResize) {
        // First check if we need to resize
        if (isResizable)
            checkResize();

        // If we are resizing, execute the put on the new array
        HashEntry<K, V>[] dataArr = (isResizing()) ? data_new : data;

        // Calculate hash
        if (hash == 0)
            hash = hash(key.hashCode());
        long offset = V_BASE + (V_SIZE * (hash % dataArr.length));

        // Iterate until element is found or not
        HashEntry<K, V> prevEntry, currentEntry;
        HashEntry<K, V> oldEntry = null;
        HashEntry<K, V> newEntry = new HashEntry<K, V>(hash, key, value); // TODO

        while (true) {
            // Get current bucket entry
            currentEntry = (HashEntry<K, V>) UNSAFE.getObjectVolatile(dataArr, offset);
            // Check if we can set new value. (Repeat if CAS fails)
            if (currentEntry == null) {
                if (!onlyReplace) {
                    if (!UNSAFE.compareAndSwapObject(dataArr, offset, currentEntry, newEntry))
                        continue;
                    if (!isResize)
                        incSize(1);
                }
                return null;
            }
            // Check if bucket entry is deleted
            if (currentEntry.isDeleted()) {
                HashEntry<K, V> nextEntry = currentEntry.getNext();
                if (nextEntry == null) {
                    if (!UNSAFE.compareAndSwapObject(dataArr, offset, currentEntry, newEntry))
                        continue;
                    if (!isResize)
                        incSize(1);
                    return null;
                } else {
                    if (!UNSAFE.compareAndSwapObject(dataArr, offset, currentEntry, nextEntry))
                        continue;
                    currentEntry = nextEntry;
                }
            }

            // Check if the bucket entry is a match
            if (!currentEntry.isDeleted() && (currentEntry.key == key || (currentEntry.hash == hash && key.equals(currentEntry.key)))) {
                oldEntry = currentEntry;
                if (onlyIfAbsent || (oldValue != null && !oldValue.equals(oldEntry.value)))
                    return oldEntry.value;
            }

            break;
        }

        // Bucket already in use, lets check the chain
        while (true) {
            if (currentEntry.getNext() == null) {
                if (!onlyReplace || (onlyReplace && oldEntry != null)) {
                    if (currentEntry.replaceNext(null, newEntry)) {
                        if (oldEntry == null && !isResize)
                            incSize(1);
                        break;
                    }
                    continue; // CAS failed, try again
                } else {
                    break;
                }
            } else {
                prevEntry = currentEntry;
                currentEntry = currentEntry.getNext();
                if (currentEntry.isDeleted()) {
                    HashEntry<K, V> nextEntry = currentEntry.getNext();
                    if (nextEntry != null)
                        prevEntry.replaceNext(currentEntry, nextEntry);
                } else if (currentEntry.key == key || (currentEntry.hash == hash && key.equals(currentEntry.key))) {
                    oldEntry = currentEntry;
                    if (onlyIfAbsent || (oldValue != null && !oldValue.equals(oldEntry.value)))
                        return oldEntry.value;
                }
            }
        }

        if (oldEntry != null) {
            oldEntry.setDeleted(0, 1); // If CAS fails entry is already marked as deleted
            return oldEntry.value;
        }
        return null;
    }

    /**
     * Removes a key/value pair from the map
     * 
     * @param k key to remove
     * @return V old value associated to the key, null if key is not in the map
     */
    @SuppressWarnings("unchecked")
    @Override
    public V remove(Object k) {
        if (k == null)
            return null;

        K key = (K) k;
        int hash = hash(key.hashCode());
        return remove(key, hash, null);
    }

    /**
     * Removes a key/value pair from the map if it matches the given parameters
     * 
     * @param k key
     * @param v value
     * @return V old value associated to the key, null if no equivalent key/value pair is found in the map
     */
    @SuppressWarnings("unchecked")
    @Override
    public boolean remove(Object k, Object v) {
        if (k == null || v == null)
            return false;

        K key = (K) k;
        V value = (V) v;
        int hash = hash(key.hashCode());

        if (remove(key, hash, value) != null)
            return true;
        return false;
    }

    /**
     * Private generic removes method used for all remove operations
     * 
     * @param key to remove
     * @param hash to avoid recomputation
     * @param value if not null only execute remove if exact key/value match is found
     * @return V old value associated to the key
     */
    @SuppressWarnings("unchecked")
    private final V remove(K key, int hash, V value) {
        long offset = V_BASE + (V_SIZE * (hash % data.length));

        // Iterate until element is found or not
        HashEntry<K, V> entry = (HashEntry<K, V>) UNSAFE.getObjectVolatile(data, offset);

        while (true) {
            // If value is found, try to set the deleted flag and return the old value if successful
            if (entry == null) {
                return null;
            } else if (!entry.isDeleted() && (entry.key == key || (entry.hash == hash && key.equals(entry.key))) && (value == null || entry.value == value || value.equals(entry.value))) {
                if (entry.setDeleted(0, 1)) {
                    decSize(1);
                    return entry.value;
                }
                return null;
            }
            entry = entry.getNext();
        }
    }

    /**
     * Replaces the value associated to a key in the map. 
     * The replace operation is only executed if the key is already present in the map 
     * 
     * @param key 
     * @param value replacement
     * @return V old value if key was already present, null otherwise
     */
    @Override
    public V replace(K key, V value) {
        if (key == null || value == null)
            return null;

        return put(key, value, 0, false, true, null, false);
    }

    /**
     * Replaces the value associated to a key in the map. 
     * The replace operation is only executed if the key is already present and the 
     * current value is equal to passed oldValue parameter. 
     * 
     * @param key 
     * @param oldValue
     * @param newValue replacement
     * @return boolean true if replace was successful
     */
    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        if (key == null || oldValue == null || newValue == null)
            return false;

        return oldValue.equals(put(key, newValue, 0, false, true, oldValue, false));
    }

    /**
     * Retrieves the size of the map
     * 
     * @return int current number of entries in the map
     */
    @Override
    public int size() {
        return UNSAFE.getIntVolatile(this, SIZE_OFFSET);
    }

    /**
     * Specifies the number of free spots before the map starts a resize procedure
     * 
     * @return int number of insert left before the map resizes
     */
    public int nextResize() {
        int res = resizeThreshold - size();
        return (res >= 0) ? res : 0;
    }

    /**
     * Internal hash function
     * Spread bits to regularize both segment and index locations using Wang/Jenkins hash.
     * 
     * @param h
     * @return int hash value
     */
    private static int hash(int h) {
        
        h += (h << 15) ^ 0xffffcd7d;
        h ^= (h >>> 10);
        h += (h << 3);
        h ^= (h >>> 6);
        h += (h << 2) + (h << 14);
        h = h ^ (h >>> 16);
        // Make it positive
        return h & 0x7fffffff;
    }

    /**
     * Internal method to atomically increase the size of the map
     * 
     * @param inc number of inserted elements
     * @return int new size of the map
     */
    private int incSize(int inc) {
        int s = UNSAFE.getIntVolatile(this, SIZE_OFFSET);
        while (!UNSAFE.compareAndSwapInt(this, SIZE_OFFSET, s, s + inc)) {
            s = UNSAFE.getIntVolatile(this, SIZE_OFFSET);
        }
        return ++s;
    }

    /**
     * Internal method to atomically decrease the size of the map
     * 
     * @param inc number of removed elements
     * @return int new size of the map
     */
    private int decSize(int dec) {
        int s = UNSAFE.getIntVolatile(this, SIZE_OFFSET);
        while (!UNSAFE.compareAndSwapInt(this, SIZE_OFFSET, s, s - dec)) {
            s = UNSAFE.getIntVolatile(this, SIZE_OFFSET);
        }
        return --s;
    }

    /**
     * Internal method that executes a resize operation if required 
     */
    @SuppressWarnings("unchecked")
    private final void checkResize() {
        if (resizeThreshold <= UNSAFE.getIntVolatile(this, SIZE_OFFSET)) {
            // Get atomic lock that guarantees one resize running at a time
            if (UNSAFE.getIntVolatile(this, RESIZE_LOCK_OFFSET) == 0) {
                if (UNSAFE.compareAndSwapInt(this, RESIZE_LOCK_OFFSET, 0, 1)) {
                    // Check size again, just for safety in case resizing fast awesomely fast
                    if (resizeThreshold > UNSAFE.getIntVolatile(this, SIZE_OFFSET))
                        return;
                    // Start resizing
                    int new_size = data.length * 2;
                    this.resizeThreshold = (int) (new_size * this.loadFactor);
                    data_new = (HashEntry<K, V>[]) new HashEntry[new_size];

                    // Copy data of data into date_new in a consistent way
                    Iterator<HashEntry<K, V>> it = new HashEntryIterator();

                    while (it.hasNext()) {
                        HashEntry<K, V> e = it.next();
                        put(e.key, e.value, e.hash, true, false, null, true);
                        e.setDeleted(0, 1);
                    }

                    data = data_new;
                    UNSAFE.putIntVolatile(this, RESIZE_LOCK_OFFSET, 0);
                }
            }
        }
    }

    /**
     * Internal function to check if the map is currently being resized
     * 
     * @return true if resize operation is currently happening
     */
    private final boolean isResizing() {
        return data_new != null && UNSAFE.getIntVolatile(this, RESIZE_LOCK_OFFSET) == 1;
    }

    /**
     * Entry object of the hash map. Contains the key/value pair as well as metadata necessary
     * for efficient and correct processing.
     * 
     * @author sloesing
     *
     * @param <K> key
     * @param <V> value
     */
    static final class HashEntry<K, V> implements Entry<K, V>
    {
        final int hash;
        final K key;
        final V value;
        volatile HashEntry<K, V> next;
        volatile int isDeleted;

        HashEntry(int hash, K key, V value) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            UNSAFE.putIntVolatile(this, deletedOffset, 0);
        }

        @Override
        public K getKey() {
            if (isDeleted())
                throw new IllegalStateException();
            return key;
        }

        @Override
        public V getValue() {
            if (isDeleted())
                throw new IllegalStateException();
            return value;
        }

        @Override
        public V setValue(V value) {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        final HashEntry<K, V> getNext() {
            return (HashEntry<K, V>) UNSAFE.getObjectVolatile(this, nextOffset);
        }

        final void setNext(HashEntry<K, V> newNext) {
            UNSAFE.putObjectVolatile(this, nextOffset, newNext);
        }

        final boolean replaceNext(HashEntry<K, V> oldNext, HashEntry<K, V> newNext) {
            return UNSAFE.compareAndSwapObject(this, nextOffset, oldNext, newNext);
        }

        final boolean setDeleted(int oldDel, int newDel) {
            return UNSAFE.compareAndSwapInt(this, deletedOffset, oldDel, newDel);
        }

        final boolean isDeleted() {
            int isDeleted = UNSAFE.getIntVolatile(this, deletedOffset);
            if (isDeleted != 0)
                return true;
            return false;
        }

        private static final long nextOffset;
        private static final long deletedOffset;
        static {
            try {
                @SuppressWarnings("rawtypes")
                Class k = HashEntry.class;
                nextOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("next"));
                deletedOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("isDeleted"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * Abstract object to iterate over the map 
     * 
     * @author sloesing
     */
    abstract class HashIterator
    {
        int mapIndex;
        HashEntry<K, V> nextEntry;
        HashEntry<K, V> lastReturned;

        HashIterator() {
            mapIndex = -1;
            nextEntry = null;
            advanceToNext();
        }

        @SuppressWarnings("unchecked")
        final void advanceToNext() {
            if (nextEntry == null || nextEntry.getNext() == null) {
                while (true) {
                    ++mapIndex;
                    if (mapIndex == data.length) {
                        nextEntry = null;
                        return;
                    }
                    HashEntry<K, V> e = (HashEntry<K, V>) UNSAFE.getObjectVolatile(data, V_BASE + (V_SIZE * mapIndex));
                    if (e == null)
                        continue;

                    while (e.isDeleted()) {
                        if ((e = e.getNext()) == null)
                            break;
                    }
                    if (e == null)
                        continue;
                    nextEntry = e;
                    break;
                }
            } else {
                nextEntry = nextEntry.getNext();
            }
        }

        final HashEntry<K, V> nextEntry() {
            if (nextEntry == null)
                throw new NoSuchElementException();
            lastReturned = nextEntry;
            advanceToNext();
            return lastReturned;
        }

        public final boolean hasNext() {
            return nextEntry != null;
        }

        public final boolean hasMoreElements() {
            return nextEntry != null;
        }

        public final void remove() {
            if (lastReturned == null || lastReturned.isDeleted())
                throw new IllegalStateException();
            LockFreeHashMap.this.remove(lastReturned.key);
            lastReturned = null;
        }
    }

    /**
     * Iterator for keys
     * 
     * @author sloesing
     */
    final class KeyIterator extends HashIterator implements Iterator<K>
    {
        public final K next() {
            return super.nextEntry().key;
        }
    }

    /**
     * Iterator for values
     * 
     * @author sloesing
     */
    final class ValueIterator extends HashIterator implements Iterator<V>
    {
        public final V next() {
            return super.nextEntry().value;
        }
    }

    /**
     * Iterator for entry objects
     * 
     * @author sloesing
     */
    final class EntryIterator extends HashIterator implements Iterator<Entry<K, V>>
    {
        public Entry<K, V> next() {
            return super.nextEntry();
        }
    }

    /**
     * Internal iterator for hash entry objects. Used for resizing.
     * 
     * @author sloesing
     */
    final private class HashEntryIterator extends HashIterator implements Iterator<HashEntry<K, V>>
    {
        public HashEntry<K, V> next() {
            return super.nextEntry();
        }
    }

    /**
     * Set of all keys for this map. 
     * 
     * @author sloesing
     */
    final class KeySet extends AbstractSet<K>
    {
        @Override
        public Iterator<K> iterator() {
            return new KeyIterator();
        }

        @Override
        public int size() {
            return LockFreeHashMap.this.size();
        }

        @Override
        public boolean isEmpty() {
            return LockFreeHashMap.this.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return LockFreeHashMap.this.containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            return LockFreeHashMap.this.remove(o) != null;
        }

        @Override
        public void clear() {
            LockFreeHashMap.this.clear();
        }

    }

    /**
     * Collection of all values for this map. 
     * 
     * @author sloesing
     */
    final class Values extends AbstractCollection<V>
    {
        @Override
        public Iterator<V> iterator() {
            return new ValueIterator();
        }

        @Override
        public int size() {
            return LockFreeHashMap.this.size();
        }

        @Override
        public boolean isEmpty() {
            return LockFreeHashMap.this.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return LockFreeHashMap.this.containsValue(o);
        }

        @Override
        public void clear() {
            LockFreeHashMap.this.clear();
        }
    }

    /**
     * Set of all entries for this map. 
     * 
     * @author sloesing
     */
    final class EntrySet extends AbstractSet<Entry<K, V>>
    {
        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean contains(Object o) {
            if (!(o instanceof Entry))
                return false;
            Entry<K, V> e = (Entry<K, V>) o;
            V v = LockFreeHashMap.this.get(e.getKey());
            return v != null && v.equals(e.getValue());
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean remove(Object o) {
            if (!(o instanceof Entry))
                return false;
            Entry<K, V> e = (Entry<K, V>) o;
            return LockFreeHashMap.this.remove(e.getKey(), e.getValue());
        }

        @Override
        public int size() {
            return LockFreeHashMap.this.size();
        }

        @Override
        public boolean isEmpty() {
            return LockFreeHashMap.this.isEmpty();
        }

        @Override
        public void clear() {
            LockFreeHashMap.this.clear();
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long SIZE_OFFSET;
    private static final long RESIZE_LOCK_OFFSET;
    private static final long V_BASE;
    private static final long V_SIZE;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
            @SuppressWarnings("rawtypes")
            Class m = LockFreeHashMap.class;
            SIZE_OFFSET = UNSAFE.objectFieldOffset(m.getDeclaredField("size"));
            RESIZE_LOCK_OFFSET = UNSAFE.objectFieldOffset(m.getDeclaredField("resizeLock"));
            @SuppressWarnings("rawtypes")
            Class e = HashEntry[].class;
            V_BASE = UNSAFE.arrayBaseOffset(e);
            V_SIZE = UNSAFE.arrayIndexScale(e);
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}