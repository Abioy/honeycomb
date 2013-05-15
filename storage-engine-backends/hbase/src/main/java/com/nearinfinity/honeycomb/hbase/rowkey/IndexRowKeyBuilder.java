/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 * Copyright 2013 Altamira Corporation.
 */


package com.nearinfinity.honeycomb.hbase.rowkey;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.nearinfinity.honeycomb.mysql.QueryKey;
import com.nearinfinity.honeycomb.mysql.Row;
import com.nearinfinity.honeycomb.mysql.schema.ColumnSchema;
import com.nearinfinity.honeycomb.mysql.schema.TableSchema;
import com.nearinfinity.honeycomb.util.Verify;
import org.apache.hadoop.hbase.util.Bytes;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * A builder for creating {@link IndexRowKey} instances.  Builder instances can be reused as it is safe
 * to call {@link #build} multiple times.
 */
public class IndexRowKeyBuilder {
    private static final long INVERT_SIGN_MASK = 0x8000000000000000L;
    private long tableId;
    private long indexId;
    private SortOrder order;
    private String indexName;
    private TableSchema tableSchema;
    private Map<String, ByteBuffer> fields;
    private UUID uuid;

    private IndexRowKeyBuilder() {
    }

    /**
     * Creates a builder with the specified table and index identifiers.
     *
     * @param tableId The valid table id that the {@link IndexRowKey} will correspond to
     * @param indexId The valid index id used to identify the {@link IndexRowKey}
     * @return The current builder instance
     */
    public static IndexRowKeyBuilder newBuilder(long tableId,
                                                long indexId) {
        Verify.isValidId(tableId);
        Verify.isValidId(indexId);
        IndexRowKeyBuilder builder = new IndexRowKeyBuilder();
        builder.tableId = tableId;
        builder.indexId = indexId;

        return builder;
    }

    private static byte[] reverseValue(byte[] value) {
        byte[] reversed = new byte[value.length];
        for (int i = 0; i < value.length; i++) {
            reversed[i] = (byte) (~value[i] & 0xFF);
        }
        return reversed;
    }

    private static byte[] encodeValue(final ByteBuffer value, final ColumnSchema columnSchema) {
        try {
            switch (columnSchema.getType()) {
                case LONG:
                case TIME: {
                    final long longValue = value.getLong();
                    return Bytes.toBytes(longValue ^ INVERT_SIGN_MASK);
                }
                case DOUBLE: {
                    final double doubleValue = value.getDouble();
                    final long longValue = Double.doubleToLongBits(doubleValue);
                    if (doubleValue < 0.0) {
                        return Bytes.toBytes(~longValue);
                    }

                    return Bytes.toBytes(longValue ^ INVERT_SIGN_MASK);
                }
                case BINARY:
                case STRING: {
                    return Arrays.copyOf(value.array(), columnSchema.getMaxLength());
                }
                default:
                    return value.array();
            }
        } finally {
            value.rewind(); // rewind the ByteBuffer's index pointer
        }
    }

    /**
     * Adds the specified {@link SortOrder} to the builder instance being constructed
     *
     * @param order The sort order to use during the build phase, not null
     * @return The current builder instance
     */
    public IndexRowKeyBuilder withSortOrder(SortOrder order) {
        checkNotNull(order, "Order must not be null");
        this.order = order;
        return this;
    }

    /**
     * Set the values of the index row based on a sql row. If an index column is
     * missing from the sql row it is replaced with an explicit null. (This
     * method is intended for insert)
     *
     * @param row         SQL row
     * @param indexName   Columns in the index
     * @param tableSchema Table schema
     * @return The current builder instance
     */
    public IndexRowKeyBuilder withRow(Row row,
                                      String indexName,
                                      TableSchema tableSchema) {
        checkNotNull(row, "row must not be null.");
        Map<String, ByteBuffer> recordCopy = Maps.newHashMap(row.getRecords());
        for (String column : tableSchema.getIndexSchema(indexName).getColumns()) {
            if (!recordCopy.containsKey(column)) {
                recordCopy.put(column, null);
            }
        }

        this.fields = recordCopy;
        this.indexName = indexName;
        this.tableSchema = tableSchema;
        return this;
    }

    /**
     * Set the values of the index row based on a QueryKey.
     *
     * @param queryKey    Query key
     * @param tableSchema Table schema
     * @return The current builder instance
     */
    public IndexRowKeyBuilder withQueryKey(QueryKey queryKey,
                                           TableSchema tableSchema) {
        checkNotNull(queryKey, "queryKey must not be null.");
        checkNotNull(tableSchema, "tableSchema must not be null.");
        this.fields = queryKey.getKeys();
        this.indexName = queryKey.getIndexName();
        this.tableSchema = tableSchema;
        return this;
    }

    /**
     * Adds the specified {@link UUID} to the builder instance being constructed
     *
     * @param uuid The identifier to use during the build phase, not null
     * @return The current builder instance
     */
    public IndexRowKeyBuilder withUUID(UUID uuid) {
        checkNotNull(uuid, "UUID must not be null");
        this.uuid = uuid;
        return this;
    }

    /**
     * Creates an {@link IndexRowKey} instance with the parameters supplied to the builder.
     * Precondition:
     *
     * @return A new row instance constructed by the builder
     */
    public IndexRowKey build() {
        checkState(order != null, "Sort order must be set on IndexRowBuilder.");
        List<byte[]> encodedRecords = Lists.newArrayList();
        if (fields != null) {
            for (String column : tableSchema.getIndexSchema(indexName).getColumns()) {
                if (!fields.containsKey(column)) {
                    continue;
                }
                ByteBuffer record = fields.get(column);
                if (record != null) {
                    byte[] encodedRecord = encodeValue(record,
                            tableSchema.getColumnSchema(column));
                    encodedRecords.add(order == SortOrder.Ascending
                            ? encodedRecord
                            : reverseValue(encodedRecord));
                } else {
                    encodedRecords.add(null);
                }
            }
        }

        if (order == SortOrder.Ascending) {
            return new AscIndexRowKey(tableId, indexId, encodedRecords, uuid);
        }

        return new DescIndexRowKey(tableId, indexId, encodedRecords, uuid);
    }

    /**
     * Representation of the rowkey associated with an index in descending order
     * for data row content
     */
    private static class DescIndexRowKey extends IndexRowKey {
        private static final byte PREFIX = 0x08;
        private static final byte[] NOT_NULL_BYTES = {0x00};
        private static final byte[] NULL_BYTES = {0x01};

        public DescIndexRowKey(final long tableId, final long indexId,
                               final List<byte[]> records, final UUID uuid) {
            super(tableId, indexId, records, uuid, PREFIX, NOT_NULL_BYTES, NULL_BYTES, SortOrder.Descending);
        }
    }

    /**
     * Representation of the rowkey associated with an index in ascending order
     * for data row content
     */
    private static class AscIndexRowKey extends IndexRowKey {
        private static final byte PREFIX = 0x07;
        private static final byte[] NOT_NULL_BYTES = {0x01};
        private static final byte[] NULL_BYTES = {0x00};

        public AscIndexRowKey(final long tableId, final long indexId,
                              final List<byte[]> records, final UUID uuid) {
            super(tableId, indexId, records, uuid, PREFIX, NOT_NULL_BYTES, NULL_BYTES, SortOrder.Ascending);
        }
    }
}
