package com.nearinfinity.honeycomb;

import com.nearinfinity.honeycomb.mysql.gen.IndexSchema;
import com.nearinfinity.honeycomb.mysql.gen.TableSchema;

/**
 * The store is responsible for meta operations on tables: opening, creating,
 * altering, deleting, and retrieving metadata.
 */
public interface Store {

    /**
     * Return the table
     *
     * @param tableName The name of the table
     * @return the table
     */
    public Table openTable(String tableName);

    /**
     * Create a table, or if the table already exists with the same name and
     * columns, open it.
     *
     * @param tableName
     * @param schema
     * @
     */
    public void createTable(String tableName, TableSchema schema);

    /**
     * Delete the specified table
     *
     * @param tableName name of the table
     */
    public void deleteTable(String tableName);

    /**
     * Renames the specified existing table to the provided table name
     *
     * @param curTableName
     * @param newTableName
     */
    public void renameTable(String curTableName, String newTableName);

    /**
     * Return the table's schema
     *
     * @param tableName The table name
     * @return The table's schema
     */
    public TableSchema getSchema(String tableName);

    /**
     * Add index to the table specified by tableName
     *
     * @param tableName The name of the table to be altered
     * @param indexName The name of the new index
     * @param schema    The schema of the index
     */
    public void addIndex(String tableName, String indexName, IndexSchema schema);

    /**
     * Drop index from the table
     *
     * @param tableName The name of the table to be altered
     * @param indexName The name of the index to be dropped
     */
    public void dropIndex(String tableName, String indexName);

    /**
     * Gets the current value of the auto increment column in the table
     *
     * @param tableName
     * @return
     */
    public long getAutoInc(String tableName);

    /**
     * Set the table's auto increment value to the greater of value and the
     * table's current auto increment value.
     *
     * @param tableName
     * @param value
     * @return
     */
    public void setAutoInc(String tableName, long value);

    /**
     * Increment the table's auto increment value by amount, and return the new
     * value (post increment).
     *
     * @param tableName Name of table
     * @param amount    Amount to auto increment by
     * @return
     */
    public long incrementAutoInc(String tableName, long amount);

    /**
     * Reset the table's auto increment value to 1.
     *
     * @param tableName Name of table
     */
    public void truncateAutoInc(String tableName);

    /**
     * Get the table's row count
     *
     * @param tableName the table
     * @return row count
     */
    public long getRowCount(String tableName);

    /**
     * Increment the table's row count by amount.
     *
     * @param tableName Name of table
     * @param amount    Amount to increment by
     * @return New row count
     */
    public long incrementRowCount(String tableName, long amount);

    /**
     * Truncate the table's row count.
     *
     * @param tableName Name of table
     */
    public void truncateRowCount(String tableName);
}
