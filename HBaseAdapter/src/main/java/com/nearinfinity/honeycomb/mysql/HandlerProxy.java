package com.nearinfinity.honeycomb.mysql;

import com.nearinfinity.honeycomb.Store;
import com.nearinfinity.honeycomb.Table;
import com.nearinfinity.honeycomb.hbase.HBaseTableFactory;
import com.nearinfinity.honeycomb.mysql.gen.TableSchema;

import java.io.IOException;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;

public class HandlerProxy {
    private final StoreFactory storeFactory;
    private Store store;
    private Table table;
    private String tableName;

    public HandlerProxy(StoreFactory storeFactory) throws Exception {
        this.storeFactory = storeFactory;
    }

    /**
     * Create a table with the given specifications.  The table is not open when
     * this is called.
     *
     * @param tableName             Name of the table
     * @param tableSpace            Indicates what store to create the table in.  If null,
     *                              create the table in the default store.
     * @param serializedTableSchema Serialized TableSchema avro object
     * @param autoInc               Initial auto increment value
     * @throws Exception
     */
    public void createTable(String tableName, String tableSpace,
                            byte[] serializedTableSchema, long autoInc) throws Exception {
        Verify.isNotNullOrEmpty(tableName);
        checkNotNull(serializedTableSchema);

        this.store = this.storeFactory.createStore(tableSpace);
        TableSchema tableSchema = Util.deserializeTableSchema(serializedTableSchema);
        store.createTable(tableName, tableSchema);
        store.incrementAutoInc(tableName, autoInc);
    }

    /**
     * Drop the table with the given specifications.  The table is not open when
     * this is called.
     *
     * @param tableName     Name of the table to be dropped
     * @param tableSpace    What store to drop table from.  If null, use default.
     * @throws Exception
     */
    public void dropTable(String tableName, String tableSpace) throws Exception {
        Verify.isNotNullOrEmpty(tableName);
        Store store = this.storeFactory.createStore(tableSpace);
        Table table = store.openTable(tableName);
        table.deleteAllRows();
        table.close();
        store.deleteTable(tableName);
    }

    public void openTable(String tableName, String tableSpace) throws Exception {
        Verify.isNotNullOrEmpty(tableName);
        this.tableName = tableName;
        this.store = this.storeFactory.createStore(tableSpace);
        this.table = this.store.openTable(this.tableName);
    }

    public void closeTable() throws IOException {
        this.tableName = null;
        this.store = null;
        this.table.close();
        this.table = null;
    }

    public String getTableName() {
        return tableName;
    }

    /**
     * Updates the existing SQL table name representation in the underlying
     * {@link Store} implementation to the specified new table name
     *
     * @param newName The new table name to represent, not null or empty
     * @throws Exception
     */
    public void renameTable(final String newName) throws Exception {
        Verify.isNotNullOrEmpty(newName, "New table name must have value.");
        checkTableOpen();

        store.renameTable(tableName, newName);
        tableName = newName;
    }

    public long getRowCount() throws Exception {
        checkTableOpen();

        return this.store.getRowCount(this.tableName);
    }

    public long getAutoIncValue()
            throws Exception {
        checkTableOpen();
        if (!Verify.hasAutoIncrementColumn(store.getSchema(tableName))) {
            throw new IllegalArgumentException(format("Table %s is not an autoincrement table.", this.tableName));
        }

        return store.getAutoInc(tableName);
    }

    public long incrementAutoIncrementValue(long amount) throws Exception {
        checkTableOpen();
        if (!Verify.hasAutoIncrementColumn(store.getSchema(tableName))) {
            throw new IllegalArgumentException(format("Column %s is not an autoincrement column.", this.tableName));
        }

        return this.store.incrementAutoInc(this.getTableName(), amount);
    }

    public void alterTable(byte[] newSchemaSerialized) throws Exception {
        checkNotNull(newSchemaSerialized);
        checkTableOpen();
        TableSchema newSchema = Util.deserializeTableSchema(newSchemaSerialized);
        this.store.alterTable(this.tableName, newSchema);
    }

    public void truncateAutoIncrement() throws Exception {
        checkTableOpen();
        this.store.truncateAutoInc(this.tableName);
    }

    public void incrementRowCount(int amount) throws Exception {
        checkTableOpen();

        this.store.incrementRowCount(this.tableName, amount);
    }

    public void truncateRowCount() throws Exception {
        checkTableOpen();
        this.store.truncateRowCount(this.tableName);
    }

    public void insert(Row row) {
        checkTableOpen();
        this.table.insert(row);
    }

    public void flush() throws IOException {
        checkTableOpen();
        this.table.flush();
    }

    public Row getRow(UUID uuid) {
        checkTableOpen();
        return this.table.get(uuid);
    }

    private void checkTableOpen() {
        checkState(table != null, "Table must be opened before used.");
    }
}
