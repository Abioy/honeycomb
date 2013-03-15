package com.nearinfinity.honeycomb;

import com.nearinfinity.honeycomb.mysql.Row;

import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;

/**
 * A Table handles operations for a single MySQL table.  It must support insert,
 * update, delete and get operations on rows, as well as table and index scans
 */
public interface Table extends Closeable {
    /**
     * Insert row into table
     *
     * @param row Row to be inserted
     */
    public void insert(Row row) throws IOException, TableNotFoundException;

    /**
     * Update row in table
     *
     * @param row Row containing UUID of row to be updated, as well as updated
     *            record values.
     * @throws IOException
     * @throws RowNotFoundException
     */
    public void update(Row row) throws IOException, RowNotFoundException;

    /**
     * Remove row with given UUID from the table
     *
     * @param uuid UUID of row to be deleted
     * @throws IOException
     * @throws RowNotFoundException
     */
    public void delete(UUID uuid) throws IOException, RowNotFoundException;

    /**
     * Flush all inserts, updates, and deletes to the table.  IUD operations are
     * not guaranteed to be visible in subsequent accesses until explicitly flushed.
     */
    public void flush() throws IOException;

    /**
     * Get row with uuid from table
     *
     * @param uuid UUID of requested row
     * @return Row with given UUID
     */
    public Row get(UUID uuid) throws RowNotFoundException, IOException;

    /**
     * Create a scanner for an unordered full table scan
     *
     * @return Scanner over table
     */
    public Scanner tableScan() throws IOException;

    /**
     * Return a scanner over the table's index at the specified key / values in
     * ascending sort.
     *
     * @return Scanner over index
     */
    public Scanner ascendingIndexScanAt(/* KeyValueContainer keyValues */);

    /**
     * Return a scanner over the table's index after the specified key / values
     * in ascending sort.
     *
     * @return Scanner over index
     */
    public Scanner ascendingIndexScanAfter(/* KeyValueContainer keyValues */);

    /**
     * Return a scanner over the table's index at the specified key / values in
     * descending sort.
     *
     * @return Scanner over index
     */
    public Scanner descendingIndexScanAt(/* KeyValueContainer keyValues */);

    /**
     * Return a scanner over the table's index after the specified key / values
     * in descending sort.
     *
     * @return Scanner over index
     */
    public Scanner descendingIndexScanAfter(/* KeyValueContainer keyValues */);

    /**
     * Return a scanner over the rows in the table with the specified key /values
     *
     * @return Scanner over index
     */
    public Scanner indexScanExact(/* KeyValueContainer keyValues */);

    /**
     * Remove all rows from the table.
     */
    void deleteAllRows() throws IOException, TableNotFoundException;
}