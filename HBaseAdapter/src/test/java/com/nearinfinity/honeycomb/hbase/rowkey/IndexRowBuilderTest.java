package com.nearinfinity.honeycomb.hbase.rowkey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.nearinfinity.honeycomb.mysql.gen.ColumnSchema;
import com.nearinfinity.honeycomb.mysql.gen.IndexSchema;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class IndexRowBuilderTest {

    private static final int ASC_INDEX_PREFIX = 0x07;

    private static final long TABLE_ID = 1;
    private static final long INDEX_ID = 5;

    private IndexRowBuilder builder;

    @Before
    public void setupTestCases() {
        builder = IndexRowBuilder.newBuilder(TABLE_ID, INDEX_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNewBuilderInvalidTableId() {
        final long invalidTableId = -1;
        IndexRowBuilder.newBuilder(invalidTableId, INDEX_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNewBuilderInvalidIndexId() {
        final long invalidIndexId = -1;
        IndexRowBuilder.newBuilder(TABLE_ID, invalidIndexId);
    }

    @Test
    public void testNewBuilder() {
        assertNotNull(IndexRowBuilder.newBuilder(TABLE_ID, INDEX_ID));
    }


    @Test(expected = NullPointerException.class)
    public void testBuilderNullSortOrder() {
        builder.withSortOrder(null);
    }

    @Test
    public void testBuilderSortOrder() {
        assertNotNull(builder.withSortOrder(SortOrder.Descending));
    }

    @Test(expected = NullPointerException.class)
    public void testBuilderNullUUID() {
        builder.withUUID(null);
    }

    @Test
    public void testBuilderUUID() {
        assertNotNull(builder.withUUID(UUID.randomUUID()));
    }

    @Test(expected = NullPointerException.class)
    public void testBuilderRecordsNullRecords() {
        builder.withQueryValues(null,
                getColumns(),
                ImmutableMap.<String, ColumnSchema>of());
    }

    @Test(expected = NullPointerException.class)
    public void testBuilderRecordsNullColumnTypes() {
        builder.withQueryValues(ImmutableMap.<String, ByteBuffer>of(),
                null, ImmutableMap.<String, ColumnSchema>of());
    }

    @Test(expected = NullPointerException.class)
    public void testBuilderRecordsNullColumnOrder() {
        builder.withQueryValues(ImmutableMap.<String, ByteBuffer>of(),
                getColumns(),
                null);
    }

    @Test
    public void testBuilderRecords() {
        builder.withQueryValues(ImmutableMap.<String, ByteBuffer>of(),
                getColumns(),
                ImmutableMap.<String, ColumnSchema>of());
    }

    @Test
    public void testBuildAscendingIndex() {
        final IndexRow row = builder.withSortOrder(SortOrder.Ascending).build();

        assertEquals(ASC_INDEX_PREFIX, row.getPrefix());
    }

    @Test(expected = IllegalStateException.class)
    public void testBuildWithoutSortOrderFails(){
        builder.build();
    }

    private List<String> getColumns() {
        return new IndexSchema(ImmutableList.<String>of(), false).getColumns();
    }
 }