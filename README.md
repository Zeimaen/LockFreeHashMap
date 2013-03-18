LockFreeHashMap
===============

LockFreeHashMap is a Java HashMap implementation based on the Java ConcurrentMap interface.

The map provides the same features as the widely used java.util.concurrent.ConcurrentHashMap
from the java SDK. However, it is completely lock free and does not use the latter to guarantee 
concurrent access. Instead, it relies on the low-level Compare-And-Swap (CAS) operation to 
process concurrent updates while keeping the map in a consistent state. 

By removing locks we prevent that threads are getting blocked and have to wait until another 
thread releases the lock. As threads are never blocked, we can achieve a much higher throughput 
than the traditional lock-based approaches. In addition, we are not limited in the number of CPU 
cores that are utilized. Indeed, we lock-free algorithms can virtually scale to an unlimited number
of cores.

Java 7+ is a requirement.
    

Implementation
--------------

For a basic explanation on how HashMaps work, please refer to the rich documentation available
on the internet. 

This map is based on the straight-forward open hashing mechanism. This means, once a collision 
occurs items are appended to the bucket and form a chain. After several collisions on a specific 
hash-key we end up with a linked list of all the items that match this bucket.

To read an item, we identify the corresponding bucket an iterate through the item chain until we 
find the matching key and return the associated value. If the key is not found, a null-value is 
returned.

To insert an item, we identify the corresponding bucket and append the item to the end of the 
chain. In case the key of the item was already in the map the insert operation returns false.
To update the value of a key, the replace function can be used.     

Both reads and writes are executed without using any locks. If two inserts happen in parallel on 
the same bucket, the CAS operation that appends the item at the end of the chain will fail for one 
of the two inserts. In this case, the operation is automatically retried and the insert returns 
successfully. 


Resizing Dynamic
----------------

By default, the HashMap is initialized with a capacity of 128 buckets. Once the map is 
filled up to 65%, it is automatically and transparently resized. Both, parameters can
be modified at construction. 

During resizing, a new bucket array with twice the size of the previous one is created. 
All items in the original array are re-hashed and inserted into the new map. Once
resizing is completed the old array is evicted and the java garbage collector will take care 
of freeing the memory. 

Resizing is again a lock-free operation. While resizing is happening, get and put operations
can be executed on the map. Read operation will search for the key in both the old and 
the new map. Put operation will be executed on the new map. In practice, executing both 
operations on two separate arrays is a bit more complicated. Please refer to the code for more 
details.


Next Steps
----------

* Bugfixing: Right now, in rare cases updates can get lost while performing a resize. Working on a fix has high priority!  
* Performance benchmarking: Compare performance to alternative hash-map implementations.
* Detailed profiling, garbage collection analysis and performance optimizations.
* Explore alternative HashMap algorithms (e.g. HopScotch hashing).


If you are interested in this HashMap implementation and want to contribute, feel free to 
create a fork, submit a patch or just send me a message.  