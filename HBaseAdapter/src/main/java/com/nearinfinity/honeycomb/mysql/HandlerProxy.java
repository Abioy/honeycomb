package com.nearinfinity.honeycomb.mysql;

import com.nearinfinity.honeycomb.Store;
import com.nearinfinity.honeycomb.Table;
import com.nearinfinity.honeycomb.mysql.gen.TableSchema;

import static com.google.common.base.Preconditions.checkNotNull;
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
     * Create a table with the given specifications
     *
     * @param databaseName          Database containing the table
     * @param tableName             Name of the table
     * @param tableSpace            Indicates what store to create the table in.  If null,
     *                              create the table in the default store.
     * @param serializedTableSchema Serialized TableSchema avro object
     * @param autoInc               Initial auto increment value
     * @throws Exception
     */
    public void createTable(String databaseName, String tableName, String tableSpace,
                            byte[] serializedTableSchema, long autoInc) throws Exception {
        Verify.isNotNullOrEmpty(tableName);
        this.store = this.storeFactory.createStore(tableSpace);
        TableSchema tableSchema = Util.deserializeTableSchema(serializedTableSchema);
        store.createTable(fullyQualifyTable(databaseName, tableName), tableSchema);
        if (autoInc > 0) {
            store.incrementAutoInc(tableName, autoInc);
        }
    }

    public void openTable(String databaseName, String tableName, String tableSpace) throws Exception {
        Verify.isNotNullOrEmpty(tableName);
        this.store = this.storeFactory.createStore(tableSpace);
        this.tableName = tableName;
        this.table = this.store.openTable(fullyQualifyTable(databaseName, tableName));
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

        store.renameTable(tableName, newName);
        tableName = newName;
    }

    public long getAutoIncValue(String columnName)
            throws Exception {
        if (!Verify.isAutoIncColumn(columnName, store.getTableMetadata(tableName))) {
            throw new IllegalArgumentException("Column " + columnName +
                    " is not an autoincrement column.");
        }

        return store.getAutoInc(tableName);
    }

    public void dropTable() throws Exception {
        this.store.deleteTable(this.tableName);
    }

    public void alterTable(byte[] newSchemaSerialized) throws Exception {
        checkNotNull(newSchemaSerialized);
        TableSchema newSchema = Util.deserializeTableSchema(newSchemaSerialized);
        this.store.alterTable(this.tableName, newSchema);
    }

    private String fullyQualifyTable(String databaseName, String tableName) {
        return format("%s.%s", databaseName, tableName);
    }
}