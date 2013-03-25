package com.nearinfinity.honeycomb;

import com.nearinfinity.honeycomb.mysql.IndexKey;
import com.nearinfinity.honeycomb.mysql.Row;

import java.util.UUID;

/**
 * A Table handles operations for a single MySQL table.  It must support "insert",
 * "update", "delete" and "get" operations on rows, as well as table and index scans
 */
public interface Table {
    /**
     * Insert row into table
     *
     * @param row Row to be inserted
     */
    void insert(Row row);

    /**
     * Update row in table
     *
     * @param row Row containing UUID of row to be updated, as well as updated
     *            record values.
     * @throws RowNotFoundException
     * @
     */
    void update(Row row);

    /**
     * Remove row with given UUID from the table
     *
     * @param uuid UUID of row to be deleted
     * @throws RowNotFoundException
     * @
     */
    void delete(UUID uuid);

    /**
     * Flush all inserts, updates, and deletes to the table.  IUD operations are
     * not guaranteed to be visible in subsequent accesses until explicitly flushed.
     */
    void flush();

    /**
     * Get row with uuid from table
     *
     * @param uuid UUID of requested row
     * @return Row with given UUID
     */
    Row get(UUID uuid);

    /**
     * Create a scanner for an unordered full table scan
     *
     * @return Scanner over table
     */
    Scanner tableScan();

    /**
     * Return a scanner over the table's index at the specified key / values in
     * ascending sort.
     *
     * @return Scanner over index
     * @param key
     */
    Scanner ascendingIndexScanAt(/* KeyValueContainer keyValues */IndexKey key);

    /**
     * Return a scanner over the table's index after the specified key / values
     * in ascending sort.
     *
     * @return Scanner over index
     * @param key
     */
    Scanner ascendingIndexScanAfter(IndexKey key);

    /**
     * Return a scanner over the table's index at the specified key / values in
     * descending sort.
     *
     * @return Scanner over index
     * @param key
     */
    Scanner descendingIndexScanAt(/* KeyValueContainer keyValues */IndexKey key);

    /**
     * Return a scanner over the table's index after the specified key / values
     * in descending sort.
     *
     * @return Scanner over index
     * @param key
     */
    Scanner descendingIndexScanAfter(/* KeyValueContainer keyValues */IndexKey key);

    /**
     * Return a scanner over the rows in the table with the specified key /values
     *
     * @return Scanner over index
     */
    Scanner indexScanExact(IndexKey key);

    /**
     * Remove all rows from the table.
     */
    void deleteAllRows();

    /**
     * Close the table.
     */
    void close();
}