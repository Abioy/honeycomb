package com.nearinfinity.honeycomb.mysql;

import com.google.common.collect.Lists;
import com.nearinfinity.honeycomb.RowNotFoundException;
import com.nearinfinity.honeycomb.hbaseclient.Constants;
import com.nearinfinity.honeycomb.mysql.gen.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.fest.assertions.Assertions.assertThat;

public class HandleProxyIntegrationTest {
    public static final String COLUMN1 = "c1";
    public static final String COLUMN2 = "c2";
    public static final String INDEX2 = "i2";
    public static final String INDEX1 = "i1";
    private static final String tableName = "db/test";
    private static HandlerProxyFactory factory;

    public static void suiteSetup() throws IOException, SAXException, ParserConfigurationException {
        factory = Bootstrap.startup();
    }

    public static void testSuccessfulRename() {
        final String newTableName = "db2/test2";
        TableSchema schema = getTableSchema();

        testProxy("Testing rename", schema, new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                proxy.renameTable(tableName, Constants.HBASE_TABLESPACE, newTableName);
                assertThat(newTableName).isEqualTo(proxy.getTableName());
                proxy.renameTable(newTableName, Constants.HBASE_TABLESPACE, tableName);
            }
        });
    }

    public static void testSuccessfulAlter() {
        final TableSchema schema = getTableSchema();
        testProxy("Testing alter", schema, new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                schema.getColumns().put("c3", new ColumnSchema(ColumnType.LONG, false, false, 8, 0, 0));
                proxy.alterTable(Util.serializeTableSchema(schema));
            }
        });
    }

    public static void testGetAutoIncrement() {
        TableSchema schema = getTableSchema();
        schema.getColumns().put("c1", new ColumnSchema(ColumnType.LONG, true, true, 8, 0, 0));
        testProxy("Testing auto increment", schema, new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                long autoIncValue = proxy.getAutoIncValue();
                assertThat(autoIncValue).isEqualTo(1);

            }
        });
    }

    public static void testIncrementAutoIncrement() {
        TableSchema schema = getTableSchema();
        schema.getColumns().put("c1", new ColumnSchema(ColumnType.LONG, true, true, 8, 0, 0));
        testProxy("Testing increment auto increment", schema, new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                long autoIncValue = proxy.incrementAutoIncrementValue(1);
                assertThat(autoIncValue).isEqualTo(2).isEqualTo(proxy.getAutoIncValue());
            }
        });
    }

    public static void testTruncateAutoInc() {
        TableSchema schema = getTableSchema();
        schema.getColumns().put("c1", new ColumnSchema(ColumnType.LONG, true, true, 8, 0, 0));
        testProxy("Testing truncate auto increment", schema, new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                proxy.truncateAutoIncrement();
                assertThat(proxy.getAutoIncValue()).isEqualTo(0);
            }
        });
    }

    public static void testGetRowCount() {
        testProxy("Testing get row count", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                proxy.incrementRowCount(2);
                assertThat(proxy.getRowCount()).isEqualTo(2);
            }
        });
    }

    public static void testTruncateRowCount() {
        testProxy("Testing truncate row count", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                proxy.incrementRowCount(5);
                proxy.truncateRowCount();
                assertThat(proxy.getRowCount()).isEqualTo(0);
            }
        });
    }

    public static void testInsertRow() {
        testProxy("Testing insert row", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                Map<String, Object> map = new HashMap<String, Object>();
                map.put(COLUMN1, ByteBuffer.allocate(8).putLong(5).rewind());
                map.put(COLUMN2, ByteBuffer.allocate(8).putLong(6).rewind());
                UUID uuid = UUID.randomUUID();
                Row row = new Row(map, uuid);
                proxy.insert(row.serialize());
                proxy.flush();
                Row result = proxy.getRow(uuid);
                assertThat(result).isEqualTo(row);
            }
        });
    }

    public static void testDeleteRow() {
        testProxy("Testing delete row", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                Map<String, Object> map = new HashMap<String, Object>();
                map.put(COLUMN1, ByteBuffer.allocate(8).putLong(5).rewind());
                map.put(COLUMN2, ByteBuffer.allocate(8).putLong(6).rewind());
                UUID uuid = UUID.randomUUID();
                Row row = new Row(map, uuid);
                proxy.insert(row.serialize());
                proxy.flush();
                proxy.deleteRow(uuid);
                proxy.flush();
                try {
                    proxy.getRow(uuid);
                } catch (RowNotFoundException e) {
                    return;
                }

                throw new AssertionError("Row was not deleted");
            }
        });
    }

    public static void testUpdateRow() {
        testProxy("Testing update row", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                UUID uuid = UUID.randomUUID();
                Map<String, Object> map = new HashMap<String, Object>();
                map.put(COLUMN1, ByteBuffer.allocate(8).putLong(5).rewind());
                map.put(COLUMN2, ByteBuffer.allocate(8).putLong(6).rewind());
                Row row = new Row(map, uuid);
                proxy.insert(row.serialize());
                proxy.flush();

                map.put(COLUMN1, ByteBuffer.allocate(8).putLong(3).rewind());
                Row newRow = new Row(map, uuid);
                proxy.updateRow(newRow.serialize());
                proxy.flush();
                Row result = proxy.getRow(uuid);
                assertThat(result).isEqualTo(newRow);
            }
        });
    }

    public static void testIndexExactScan() {
        testProxy("Testing index exact scan", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                int rows = 3;
                int keyValue = 5;
                insertData(proxy, rows, keyValue);
                IndexKey key = createKey(keyValue, QueryType.EXACT_KEY);
                assertReceivingDifferentRows(proxy, key, rows);
            }
        });
    }

    public static void testAfterKeyScan() {
        testProxy("Testing after key scan", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                int rows = 3;
                int keyValue = 5;
                insertData(proxy, 1, keyValue, Constants.FULL_UUID);
                insertData(proxy, 1, keyValue + 1, Constants.ZERO_UUID);
                insertData(proxy, rows, keyValue + 1);
                IndexKey key = createKey(keyValue, QueryType.AFTER_KEY);
                assertReceivingDifferentRows(proxy, key, rows + 2);
            }
        });
    }

    public static void testKeyOrNextScan() {
        testProxy("Testing key or next scan", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                int rows = 3;
                int keyValue = 5;
                insertData(proxy, 1, keyValue, Constants.FULL_UUID);
                insertData(proxy, 1, keyValue + 1, Constants.ZERO_UUID);
                insertData(proxy, rows, keyValue + 1);
                IndexKey key = createKey(keyValue, QueryType.KEY_OR_NEXT);
                assertReceivingDifferentRows(proxy, key, rows + 3);
            }
        });
    }

    public static void testBeforeKeyScan() {
        testProxy("Testing before key scan", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                int rows = 3;
                int keyValue = 5;
                insertNullData(proxy, 1, keyValue);
                insertData(proxy, 1, keyValue, Constants.FULL_UUID);
                insertData(proxy, 1, keyValue - 1, Constants.ZERO_UUID);
                insertData(proxy, rows, keyValue - 1);
                IndexKey key = createKey(keyValue, QueryType.BEFORE_KEY);
                assertReceivingDifferentRows(proxy, key, rows + 2);
            }
        });

    }

    public static void testKeyOrPreviousScan() {
        testProxy("Testing key or previous scan", new Action() {
            @Override
            public void execute(HandlerProxy proxy) {
                int rows = 3;
                int keyValue = 5;
                insertData(proxy, 1, keyValue, Constants.FULL_UUID);
                insertData(proxy, 1, keyValue - 1, Constants.ZERO_UUID);
                insertData(proxy, rows, keyValue - 1);
                IndexKey key = createKey(keyValue, QueryType.KEY_OR_PREVIOUS);
                assertReceivingDifferentRows(proxy, key, rows + 3);
            }
        });
    }

    public static void main(String[] args) {
        try {
            suiteSetup();
            testSuccessfulRename();
            testSuccessfulAlter();
            testGetAutoIncrement();
            testIncrementAutoIncrement();
            testTruncateAutoInc();
            testGetRowCount();
            testTruncateRowCount();
            testInsertRow();
            testDeleteRow();
            testUpdateRow();
            testIndexExactScan();
            testAfterKeyScan();
            testBeforeKeyScan();
            testKeyOrNextScan();
            testKeyOrPreviousScan();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void assertReceivingDifferentRows(HandlerProxy proxy, IndexKey key, int rows) {
        proxy.startIndexScan(key.serialize());
        Row previous = null;
        for (int x = 0; x < rows; x++) {
            Row current = proxy.getNextScannerRow();
            assertThat(current).isNotEqualTo(previous);
            previous = current;
        }

        Row end = proxy.getNextScannerRow();
        assertThat(end).isNull();
    }

    private static IndexKey createKey(int keyValue, QueryType queryType) {
        HashMap<String, ByteBuffer> keys = new HashMap<String, ByteBuffer>();
        keys.put(COLUMN1, encodeValue(keyValue));
        return new IndexKey(INDEX1, queryType, keys);
    }

    private static ByteBuffer encodeValue(long value) {
        return (ByteBuffer) ByteBuffer.allocate(8).putLong(value).rewind();
    }

    private static void testProxy(String message, Action test) {
        testProxy(message, getTableSchema(), test);
    }

    private static void testProxy(String message, TableSchema schema, Action test) {
        System.out.println(message);
        HandlerProxy proxy = factory.createHandlerProxy();
        proxy.createTable(tableName, Constants.HBASE_TABLESPACE, Util.serializeTableSchema(schema), 1);
        proxy.openTable(tableName, Constants.HBASE_TABLESPACE);
        test.execute(proxy);
        proxy.closeTable();
        proxy.dropTable(tableName, Constants.HBASE_TABLESPACE);
    }

    private static TableSchema getTableSchema() {
        HashMap<String, ColumnSchema> columns = new HashMap<String, ColumnSchema>();
        HashMap<String, IndexSchema> indices = new HashMap<String, IndexSchema>();
        columns.put(COLUMN1, new ColumnSchema(ColumnType.LONG, true, false, 8, 0, 0));
        columns.put(COLUMN2, new ColumnSchema(ColumnType.LONG, true, false, 8, 0, 0));
        indices.put(INDEX1, new IndexSchema(Lists.newArrayList(COLUMN1), false));
        indices.put(INDEX2, new IndexSchema(Lists.newArrayList(COLUMN1, COLUMN2), false));

        return new TableSchema(columns, indices);
    }

    private static void insertNullData(HandlerProxy proxy, int rows, long keyColumnValue) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(COLUMN1, encodeValue(keyColumnValue));
        for (int x = 0; x < rows; x++) {
            map.put(COLUMN2, null);
            Row row = new Row(map, UUID.randomUUID());
            proxy.insert(row.serialize());
        }
        proxy.flush();
    }

    private static void insertData(HandlerProxy proxy, int rows, long keyColumnValue, UUID uuid) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(COLUMN1, encodeValue(keyColumnValue));
        for (int x = 0; x < rows; x++) {
            map.put(COLUMN2, encodeValue(x));
            Row row = new Row(map, uuid);
            proxy.insert(row.serialize());
        }
        proxy.flush();
    }

    private static void insertData(HandlerProxy proxy, int rows, long keyColumnValue) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(COLUMN1, encodeValue(keyColumnValue));
        for (int x = 0; x < rows; x++) {
            map.put(COLUMN2, encodeValue(x));
            Row row = new Row(map, UUID.randomUUID());
            proxy.insert(row.serialize());
        }
        proxy.flush();
    }

    private interface Action {
        public void execute(HandlerProxy proxy);
    }
}