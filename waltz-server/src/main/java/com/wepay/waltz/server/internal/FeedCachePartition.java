package com.wepay.waltz.server.internal;

import com.wepay.waltz.common.message.FeedData;
import com.wepay.waltz.common.message.RecordHeader;
import com.wepay.waltz.common.message.ReqId;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements the local feed cache for a given partition.
 */
public class FeedCachePartition {

    private final int partitionId;
    private final FeedCache feedCache;
    private final LinkedHashMap<FeedCacheBlockKey, FeedCacheBlock> localPool;

    private int maxNumBlocks;
    private int refCount;
    private FeedCacheBlock frontier;

    /**
     * Class constructor.
     * @param partitionId The partition ID.
     * @param feedCache Initially assigned feed cache for a given partition.
     */
    FeedCachePartition(int partitionId, FeedCache feedCache) {
        this.partitionId = partitionId;
        this.feedCache = feedCache;
        this.frontier = null;
        this.localPool = new LinkedHashMap<>();
        this.refCount = 0;
    }

    /**
     * Opens feed cache partition and increments its reference count.
     */
    public void open() {
        synchronized (this) {
            refCount++;
        }
    }

    /**
     * Closes the feed cache partition. This decrements the reference count.
     * If the reference count reaches zero, this method actually clears the cache and
     * removes the partition from FeedCache.
     */
    public void close() {
        synchronized (this) {
            if (--refCount <= 0) {
                // No more reference. Actually clear this partition and remove it from FeedCache.
                clear();
                feedCache.removePartition(partitionId);
            }
        }
    }

    /**
     * Clears the feed cache partition. This will remove all cache blocks.
     */
    public void clear() {
        synchronized (this) {
            feedCache.checkInAll(localPool);
            localPool.clear();
            frontier = null;
        }
    }

    /**
     * Returns the number of blocks held by this partition.
     * @return number of blocks held by this partition.
     */
    int getNumBlocks() {
        synchronized (this) {
            return localPool.size();
        }
    }

    /**
     * Sets the max number of blocks held by this partition.
     * @param numBlocks the max number of blocks held by this partition.
     */
    public void setMaxNumBlocks(int numBlocks) {
        synchronized (this) {
            maxNumBlocks = numBlocks;
            reduceLocalPoolSize(numBlocks);
        }
    }

    /**
     * Gets the max number of blocks held by this partition.
     * @return the max number of blocks
     */
    public int getMaxNumBlocks() {
        synchronized (this) {
            return maxNumBlocks;
        }
    }

    /**
     * Adds a feed data to the cache.
     * @param transactionId The transaction ID.
     * @param reqId The request ID.
     * @param header The header of the given transaction ID.
     */
    public void add(long transactionId, ReqId reqId, int header) {
        synchronized (this) {
            if (refCount > 0) {
                while (frontier == null || !frontier.add(transactionId, reqId, header)) {
                    FeedCacheBlockKey key = FeedCacheBlockKey.get(partitionId, transactionId);
                    FeedCacheBlock block = localPool.get(key);
                    if (block != null) {
                        frontier = block;
                    } else {
                        frontier = checkOut(key);
                        if (frontier == null) {
                            // FeedCache is closed.
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds a list of record headers as feed data.
     * @param recordHeaderList list of record headers to be added.
     */
    public void addAll(List<RecordHeader> recordHeaderList) {
        synchronized (this) {
            if (refCount > 0) {
                FeedCacheBlock block = null;
                for (RecordHeader recordHeader : recordHeaderList) {
                    while (block == null || !block.add(recordHeader.transactionId, recordHeader.reqId, recordHeader.header)) {
                        FeedCacheBlockKey key = FeedCacheBlockKey.get(partitionId, recordHeader.transactionId);
                        block = localPool.get(key);
                        if (block == null) {
                            block = checkOut(key);
                            if (block == null) {
                                // FeedCache is closed.
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets a feed data of the specified transaction from the cache.
     * @param transactionId The transaction ID.
     * @return A feed data, or null on cache miss.
     */
    public FeedData get(long transactionId) {
        synchronized (this) {
            if (refCount > 0) {
                FeedData feedData;

                if (frontier != null) {
                    feedData = frontier.get(transactionId);
                    if (feedData != null) {
                        return feedData;
                    }
                }

                FeedCacheBlockKey key = FeedCacheBlockKey.get(partitionId, transactionId);
                FeedCacheBlock block = localPool.get(key);
                if (block == null) {
                    block = checkOut(key);

                    if (block == null) {
                        // FeedCache is closed.
                        return null;
                    }
                }

                feedData = block.get(transactionId);
                if (feedData == null) {
                    feedCache.markCacheMiss();
                }

                return feedData;

            } else {
                // The cache partition is closed.
                return null;
            }
        }
    }

    /**
     * Reduces the local pool size if the current size is greater than the specified size.
     * @param size The size of the local pool.
     */
    private void reduceLocalPoolSize(int size) {
        int numBlocksToEvict = localPool.size() - size;

        if (numBlocksToEvict > 0) {
            Iterator<Map.Entry<FeedCacheBlockKey, FeedCacheBlock>> iterator = localPool.entrySet().iterator();
            while (numBlocksToEvict > 0 && iterator.hasNext()) {
                Map.Entry<FeedCacheBlockKey, FeedCacheBlock> entry = iterator.next();
                FeedCacheBlock oldBlock = entry.getValue();

                // Evict the block only if it is not the frontier
                if (oldBlock != frontier) {
                    iterator.remove();
                    feedCache.checkIn(entry.getKey(), oldBlock);
                    numBlocksToEvict--;
                }
            }
        }
    }

    /**
     * Checks out a cache block. This returns null if FeedCache is closed.
     * @param key The key of a block to be checked out of the shared pool.
     * @return a cache block, or null if FeedCache is closed.
     */
    private FeedCacheBlock checkOut(FeedCacheBlockKey key) {
        reduceLocalPoolSize(maxNumBlocks - 1);

        FeedCacheBlock block = feedCache.checkOut(key);

        if (block != null) {
            localPool.put(key, block);
        }

        return block;
    }

}
