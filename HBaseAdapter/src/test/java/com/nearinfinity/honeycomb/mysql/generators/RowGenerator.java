package com.nearinfinity.honeycomb.mysql.generators;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

import net.java.quickcheck.Generator;

import com.google.common.collect.ImmutableMap;
import com.nearinfinity.honeycomb.mysql.Row;
import com.nearinfinity.honeycomb.mysql.schema.ColumnSchema;
import com.nearinfinity.honeycomb.mysql.schema.TableSchema;

public class RowGenerator implements Generator<Row> {
    private static final Generator<UUID> uuids = new UUIDGenerator();
    private final Map<String, Generator<ByteBuffer>> recordGenerators;

    public RowGenerator(TableSchema schema) {
        super();
        ImmutableMap.Builder<String, Generator<ByteBuffer>> recordGenerators = ImmutableMap.builder();
        for (ColumnSchema column : schema.getColumns()) {
            recordGenerators.put(column.getColumnName(), new FieldGenerator(column));
        }
        this.recordGenerators = recordGenerators.build();
    }

    @Override
    public Row next() {
        ImmutableMap.Builder<String, ByteBuffer> records = ImmutableMap.builder();
        for (Map.Entry<String, Generator<ByteBuffer>> record : recordGenerators.entrySet()) {
            ByteBuffer nextValue = record.getValue().next();
            if (nextValue != null) {
                records.put(record.getKey(), nextValue);
            }
        }
        return new Row(records.build(), uuids.next());
    }
}