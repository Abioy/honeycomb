package com.nearinfinity.honeycomb.hbase;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.jcip.annotations.GuardedBy;
import net.jcip.annotations.ThreadSafe;

import org.apache.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.BiMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.nearinfinity.honeycomb.mysql.Verify;
import com.nearinfinity.honeycomb.mysql.gen.TableSchema;

@Singleton
@ThreadSafe
public class MetadataCache {
    private static final Logger logger = Logger.getLogger(MetadataCache.class);
    @GuardedBy("readWriteLock")
    private final LoadingCache<String, Long> tableCache;
    @GuardedBy("readWriteLock")
    private final LoadingCache<Long, BiMap<String, Long>> columnsCache;
    private final LoadingCache<Long, Long> rowsCache;
    private final LoadingCache<Long, Long> autoIncCache;
    @GuardedBy("readWriteLock")
    private final LoadingCache<Long, TableSchema> schemaCache;
    private final LoadingCache<Long, Map<String, Long>> indicesCache;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    @Inject
    public MetadataCache(final HBaseMetadata metadata) {
        tableCache = CacheBuilder
                .newBuilder()
                .build(new CacheLoader<String, Long>() {
                    @Override
                    public Long load(String tableName) {
                        return metadata.getTableId(tableName);
                    }
                });

        columnsCache = CacheBuilder
                .newBuilder()
                .build(new CacheLoader<Long, BiMap<String, Long>>() {
                    @Override
                    public BiMap<String, Long> load(Long tableId) {
                        return metadata.getColumnIds(tableId);
                    }
                }
                );

        indicesCache = CacheBuilder
                .newBuilder()
                .build(new CacheLoader<Long, Map<String, Long>>() {
                    @Override
                    public Map<String, Long> load(Long tableId) {
                        return metadata.getIndexIds(tableId);
                    }
                });

        autoIncCache = CacheBuilder
                .newBuilder()
                .build(new CacheLoader<Long, Long>() {
                    @Override
                    public Long load(Long tableId) {
                        return metadata.getAutoInc(tableId);
                    }
                }
                );

        rowsCache = CacheBuilder
                .newBuilder()
                .build(new CacheLoader<Long, Long>() {
                    @Override
                    public Long load(Long tableId) {
                        return metadata.getRowCount(tableId);
                    }
                }
                );

        schemaCache = CacheBuilder
                .newBuilder()
                .build(new CacheLoader<Long, TableSchema>() {
                    @Override
                    public TableSchema load(Long tableId) {
                        return metadata.getSchema(tableId);
                    }
                }
                );
    }

    /**
     * Retrieve a table ID from the cache based on the table name.
     *
     * @param tableName Name of the table (cannot be null/empty)
     * @return Table ID
     */
    public long tableCacheGet(final String tableName) {
        Verify.isNotNullOrEmpty(tableName);
        readWriteLock.readLock().lock();
        try {
            return cacheGet(tableCache, tableName);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * Retrieve a BiMap of column name to column ID from cache based on table ID.
     *
     * @param tableId Table ID
     * @return BiMap of column name to column ID
     */
    public BiMap<String, Long> columnsCacheGet(final long tableId) {
        Verify.isValidTableId(tableId);
        readWriteLock.readLock().lock();
        try {
            return cacheGet(columnsCache, tableId);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * Retrieve a table schema from cache based on table ID.
     *
     * @param tableId Table ID
     * @return Table schema
     */
    public TableSchema schemaCacheGet(final long tableId) {
        Verify.isValidTableId(tableId);
        readWriteLock.readLock().lock();
        try {
            return cacheGet(schemaCache, tableId);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * Retrieve a map of index name to index ID from cache based on a table ID.
     *
     * @param tableId Table ID
     * @return Map of index name to index ID
     */
    public Map<String, Long> indicesCacheGet(Long tableId) {
        Verify.isValidTableId(tableId);
        return cacheGet(indicesCache, tableId);
    }

    /**
     * Retrieve the auto increment count for a table from cache.
     *
     * @param tableId Table ID
     * @return Auto increment count
     */
    public Long autoIncCacheGet(Long tableId) {
        Verify.isValidTableId(tableId);
        return cacheGet(autoIncCache, tableId);
    }

    /**
     * Retrieve the row count of a table from cache.
     *
     * @param tableId Table ID
     * @return Table row count
     */
    public Long rowsCacheGet(final long tableId) {
        Verify.isValidTableId(tableId);
        return cacheGet(rowsCache, tableId);
    }

    /**
     * Updates the row count in the cache for a table.
     *
     * @param tableId Table ID
     * @param value   New row count
     */
    public void updateRowCache(final long tableId, long value) {
        Verify.isValidTableId(tableId);
        rowsCache.put(tableId, value);
    }

    /**
     * Evict the row count from the cache for a table.
     *
     * @param tableId Table ID
     */
    public void invalidateRowCache(long tableId) {
        Verify.isValidTableId(tableId);
        rowsCache.invalidate(tableId);
    }


    /**
     * Evict the index mapping from the cache for the specified table id
     *
     * @param tableId Table ID
     */
    public void invalidateIndicesCache(long tableId) {
        Verify.isValidTableId(tableId);
        indicesCache.invalidate(tableId);
    }


    /**
     * Evict the {@link TableSchema} from the cache for the specified table id
     *
     * @param tableId Table ID
     */
    public void invalidateSchemaCache(long tableId) {
        Verify.isValidTableId(tableId);
        schemaCache.invalidate(tableId);
    }


    /**
     * Evict a table's metadata from the cache.
     *
     * @param tableName Table name
     * @param tableId   Table ID
     */
    public void invalidateCache(String tableName, long tableId) {
        Verify.isNotNullOrEmpty(tableName);
        Verify.isValidTableId(tableId);
        readWriteLock.writeLock().lock();
        try {
            tableCache.invalidate(tableName);
            columnsCache.invalidate(tableId);
            schemaCache.invalidate(tableId);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * Evict a table's auto increment count from the cache.
     *
     * @param tableId Table ID
     */
    public void invalidateAutoIncCache(long tableId) {
        Verify.isValidTableId(tableId);
        autoIncCache.invalidate(tableId);
    }

    /**
     * Updates a table's auto increment value in cache.
     *
     * @param tableId Table ID
     * @param value   New auto increment value
     */
    public void updateAutoIncCache(long tableId, long value) {
        Verify.isValidTableId(tableId);
        autoIncCache.put(tableId, value);
    }

    private static <K, V> V cacheGet(LoadingCache<K, V> cache, K key) {
        try {
            return cache.get(key);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            logger.error("Encountered unexpected exception during cache get:", cause);
            throw new RuntimeException(cause);
        }
    }
}
