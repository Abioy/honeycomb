package integrationtests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.nearinfinity.honeycomb.mysql.QueryKey;
import com.nearinfinity.honeycomb.mysql.Row;
import com.nearinfinity.honeycomb.mysql.gen.ColumnType;
import com.nearinfinity.honeycomb.mysql.gen.QueryType;
import com.nearinfinity.honeycomb.mysql.schema.ColumnSchema;
import com.nearinfinity.honeycomb.mysql.schema.IndexSchema;
import com.nearinfinity.honeycomb.mysql.schema.TableSchema;

/**
 * Integration tests for the Java side that come from MySQL integration tests.
 * If a MySQL integration test fails and it's not a C++ issue it should appear in here
 * in the format:
 * SQL query that failed
 * What was expected
 * What happened
 * Test reproducing the failure
 */
public class MySqlBugIT extends HoneycombIntegrationTest {
    private static final int ROW_COUNT = 1;
    private static final int INDEX_COL_VALUE = 7;

    /*
    create table t1 (c1 int, c2 int, unique(c1,c2));
    insert into t1 values (1,1),(1,1);
    Expected failure
     */
    @Test
    public void testRowInsertNotExcludingFieldsFromIndexKey() {
        ITUtils.insertData(proxy, 1, 1);
        Row row = ITUtils.createRow(1);
        assertTrue(proxy.indexContainsDuplicate(TestConstants.INDEX1, row.serialize()));
    }

    /*
    DROP TABLE if exists t1;
    CREATE TABLE t1(c1 BIGINT SIGNED NULL,c2 BIGINT SIGNED NULL , INDEX(c1, c2));
    INSERT INTO t1 VALUES
    (4611686018427387903, NULL),
    (4611686018427387903, NULL);

    SELECT * FROM t1 WHERE c1 = 4611686018427387903 AND c2 is null ORDER BY c1 ASC;
    Expected two rows
    Actual was zero rows
     */
    @Test
    public void testInsertBuildsIndexCorrectlyOnNullColumns() {
        final Map<String, ByteBuffer> map = Maps.newHashMap();
        map.put(TestConstants.COLUMN1, ITUtils.encodeValue(INDEX_COL_VALUE));

        final Row row = new Row(map, UUID.randomUUID());
        proxy.insertRow(row.serialize());
        proxy.flush();

        HashMap<String, ByteBuffer> keys = Maps.newHashMap();
        keys.put(TestConstants.COLUMN1, ITUtils.encodeValue(INDEX_COL_VALUE));
        keys.put(TestConstants.COLUMN2, null);

        final QueryKey key = new QueryKey(TestConstants.INDEX2, QueryType.EXACT_KEY, keys);

        ITUtils.assertReceivingDifferentRows(proxy, key, ROW_COUNT);
    }

    /*
    DROP TABLE IF EXISTS t1;
    CREATE TABLE t1(c1 INT, c2 INT, INDEX(c1));
    INSERT INTO t1 VALUES(NULL, 1);
    UPDATE t1 SET c2=2 WHERE c1 IS NULL;
    Index scan: Null, 1
    Table scan: Null, 2
     */
    @Test
    public void testUpdateNotChangingIndicesWhenUpdatedColumnNotInIndex() {
        Map<String, ByteBuffer> values = Maps.newHashMap();
        values.put(TestConstants.COLUMN2, ITUtils.encodeValue(1));
        Row row = new Row(values, UUID.randomUUID());
        proxy.insertRow(row.serialize());
        proxy.flush();

        proxy.startTableScan();
        byte[] nextRow = proxy.getNextRow();
        row = Row.deserialize(nextRow);
        proxy.endScan();
        row.getRecords().put(TestConstants.COLUMN2, ITUtils.encodeValue(2)); // update t1 set c2=2 where c1 is null
        proxy.updateRow(nextRow, row.serialize());

        Map<String, ByteBuffer> searchMap = Maps.newHashMap();
        searchMap.put(TestConstants.COLUMN1, null);
        QueryKey key = new QueryKey(TestConstants.INDEX1, QueryType.EXACT_KEY, searchMap);
        proxy.startIndexScan(key.serialize());
        Row result = Row.deserialize(proxy.getNextRow());
        assertEquals(result.getRecords().get(TestConstants.COLUMN2).getLong(), ITUtils.encodeValue(2).getLong());
        proxy.endScan();
    }

    /*
    drop table if exists t1;
    create table t1 (c1 int, unique(c1));
    insert into t1 values (null),(null);
    Expected: 2 insert
    Actual: Error duplicate entry
     */
    @Test
    public void testNullsAreNotDuplicatesInUniqueIndex() {
        ITUtils.insertNullData(proxy, 1);
        Map<String, ByteBuffer> values = Maps.newHashMap();
        values.put(TestConstants.COLUMN2, ITUtils.encodeValue(1));
        Row row = new Row(values, UUID.randomUUID());
        assertFalse(proxy.indexContainsDuplicate(TestConstants.INDEX3, row.serialize()));
    }


    /**
     * Provides the {@link com.nearinfinity.honeycomb.mysql.schema.TableSchema} to use for a test case
     *
     * @return The schema used for testing
     */
    @Override
    protected TableSchema getTableSchema() {
        final List<ColumnSchema> columns = Lists.newArrayList();
        final List<IndexSchema> indices = Lists.newArrayList();

        // Add nullable, non-autoincrementing columns
        columns.add(ColumnSchema.builder(TestConstants.COLUMN1, ColumnType.LONG).build());
        columns.add(ColumnSchema.builder(TestConstants.COLUMN2, ColumnType.LONG).build());

        // Add non-unique index on one column
        indices.add(new IndexSchema(TestConstants.INDEX1, Lists.newArrayList(TestConstants.COLUMN1), false));

        // Add non-unique compound index on (c1, c2)
        indices.add(new IndexSchema(TestConstants.INDEX2, Lists.newArrayList(TestConstants.COLUMN1, TestConstants.COLUMN2), false));

        // Add unique index on one column
        indices.add(new IndexSchema(TestConstants.INDEX3, Lists.newArrayList(TestConstants.COLUMN1), true));

        return new TableSchema(columns, indices);
    }
}
