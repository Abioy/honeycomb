package com.nearinfinity.honeycomb.mysql;

import com.nearinfinity.honeycomb.Scanner;
import com.nearinfinity.honeycomb.Store;
import com.nearinfinity.honeycomb.Table;
import com.nearinfinity.honeycomb.mysql.gen.IndexSchema;
import com.nearinfinity.honeycomb.mysql.gen.QueryType;
import com.nearinfinity.honeycomb.mysql.gen.TableSchema;

import java.util.UUID;

import static com.google.common.base.Preconditions.*;
import static java.lang.String.format;

public class HandlerProxy {
    private final StoreFactory storeFactory;
    private Store store;
    private Table table;
    private String tableName;
    private Scanner currentScanner;

    public HandlerProxy(StoreFactory storeFactory) {
        this.storeFactory = storeFactory;
    }

    /**
     * Create a table with the given specifications.  The table is not open when
     * this is called.
     *
     * @param tableName             Name of the table
     * @param tableSpace            Indicates what store to create the table in.
     *                              If null, create the table in the default store.
     * @param serializedTableSchema Serialized TableSchema avro object
     * @param autoInc               Initial auto increment value
     */
    public void createTable(String tableName, String tableSpace,
                            byte[] serializedTableSchema, long autoInc) {
        Verify.isNotNullOrEmpty(tableName);
        checkNotNull(serializedTableSchema);

        this.store = this.storeFactory.createStore(tableSpace);
        TableSchema tableSchema = Util.deserializeTableSchema(serializedTableSchema);
        Verify.isValidTableSchema(tableSchema);
        store.createTable(tableName, tableSchema);
        store.incrementAutoInc(tableName, autoInc);
    }

    /**
     * Drop the table with the given specifications.  The table is not open when
     * this is called.
     *
     * @param tableName  Name of the table to be dropped
     * @param tableSpace What store to drop table from.  If null, use default.
     */
    public void dropTable(String tableName, String tableSpace) {
        Verify.isNotNullOrEmpty(tableName);
        Store store = this.storeFactory.createStore(tableSpace);
        Table table = store.openTable(tableName);
        table.deleteAllRows();

        Util.closeQuietly(table);
        store.deleteTable(tableName);
    }

    public void openTable(String tableName, String tableSpace) {
        Verify.isNotNullOrEmpty(tableName);
        this.tableName = tableName;
        this.store = this.storeFactory.createStore(tableSpace);
        this.table = this.store.openTable(this.tableName);
    }

    public void closeTable() {
        this.tableName = null;
        this.store = null;
        Util.closeQuietly(table);
        this.table = null;
    }

    public String getTableName() {
        return tableName;
    }

    /**
     * Updates the existing SQL table name representation in the underlying
     * {@link Store} implementation to the specified new table name.  The table
     * is not open when this is called.
     *
     * @param originalName The existing name of the table, not null or empty
     * @param tableSpace   The store which contains the table
     * @param newName      The new table name to represent, not null or empty
     */
    public void renameTable(final String originalName, final String tableSpace,
                            final String newName) {
        Verify.isNotNullOrEmpty(originalName, "Original table name must have value.");
        Verify.isNotNullOrEmpty(newName, "New table name must have value.");
        checkArgument(!originalName.equals(newName), "New table name must be different than original.");

        Store store = this.storeFactory.createStore(tableSpace);
        store.renameTable(originalName, newName);
        this.tableName = newName;
    }

    public long getRowCount() {
        checkTableOpen();

        return this.store.getRowCount(this.tableName);
    }

    public long getAutoIncValue() {
        checkTableOpen();
        if (!Verify.hasAutoIncrementColumn(store.getSchema(tableName))) {
            throw new IllegalArgumentException(format("Table %s is not an autoincrement table.", this.tableName));
        }

        return store.getAutoInc(tableName);
    }

    public long incrementAutoIncrementValue(long amount) {
        checkTableOpen();
        if (!Verify.hasAutoIncrementColumn(store.getSchema(tableName))) {
            throw new IllegalArgumentException(format("Column %s is not an autoincrement column.", this.tableName));
        }

        return this.store.incrementAutoInc(this.getTableName(), amount);
    }

    public void addIndex(String indexName, byte[] serializedSchema) {
        checkNotNull(indexName);
        checkNotNull(serializedSchema);

        IndexSchema schema = Util.deserializeIndexSchema(serializedSchema);
        checkArgument(!schema.getIsUnique(), "Honeycomb does not support adding unique indices.");
        this.store.addIndex(this.tableName, indexName, schema);
    }

    public void dropIndex(String indexName) {
        checkNotNull(indexName);

        this.store.dropIndex(this.tableName, indexName);
    }

    public void truncateAutoIncrement() {
        checkTableOpen();
        this.store.truncateAutoInc(this.tableName);
    }

    public void incrementRowCount(int amount) {
        checkTableOpen();

        this.store.incrementRowCount(this.tableName, amount);
    }

    public void truncateRowCount() {
        checkTableOpen();
        this.store.truncateRowCount(this.tableName);
    }

    public void insert(byte[] rowBytes) {
        checkTableOpen();
        Row row = Row.deserialize(rowBytes);
        this.table.insert(row);
    }

    public void flush() {
        // MySQL will call flush on the handler without an open table, which is
        // a no-op
        if (this.table != null) {
            this.table.flush();
        }
    }

    public Row getRow(UUID uuid) {
        checkTableOpen();
        return this.table.get(uuid);
    }

    public void deleteRow(UUID uuid) {
        checkTableOpen();
        this.table.delete(uuid);
    }

    public void updateRow(byte[] newRowBytes) {
        checkTableOpen();
        checkNotNull(newRowBytes);
        Row newRow = Row.deserialize(newRowBytes);
        this.table.update(newRow);
    }

    public void startTableScan() {
        checkTableOpen();
        this.currentScanner = this.table.tableScan();
    }

    public void startIndexScan(byte[] indexKeys) {
        checkTableOpen();
        IndexKey key = IndexKey.deserialize(indexKeys);
        QueryType queryType = key.getQueryType();
        switch (queryType) {
            case EXACT_KEY:
                this.currentScanner = this.table.indexScanExact(key);
                break;
            case AFTER_KEY:
                this.currentScanner = this.table.ascendingIndexScanAfter(key);
                break;
            case BEFORE_KEY:
                this.currentScanner = this.table.descendingIndexScanAfter(key);
                break;
            case INDEX_FIRST:
                this.currentScanner = this.table.ascendingIndexScanAt(key);
                break;
            case INDEX_LAST:
                this.currentScanner = this.table.descendingIndexScanAt(key);
                break;
            case KEY_OR_NEXT:
                this.currentScanner = this.table.ascendingIndexScanAt(key);
                break;
            case KEY_OR_PREVIOUS:
                this.currentScanner = this.table.descendingIndexScanAt(key);
                break;
        }

    }

    public byte[] getNextRow() {
        if (!this.currentScanner.hasNext()) {
            return null;
        }

        return this.currentScanner.next().serialize();
    }

    public byte[] getRow(byte[] uuid) {
        checkTableOpen();
        return this.table.get(Util.bytesToUUID(uuid)).serialize();
    }

    public void endScan() {
        Util.closeQuietly(this.currentScanner);
    }

    private void checkTableOpen() {
        checkState(table != null, "Table must be opened before used.");
    }
}
