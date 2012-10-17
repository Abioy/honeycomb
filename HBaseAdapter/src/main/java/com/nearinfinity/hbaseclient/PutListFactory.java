package com.nearinfinity.hbaseclient;

import com.google.common.collect.ImmutableMap;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PutListFactory {
    public static List<Put> createPutList(final Map<String, byte[]> values, final TableInfo info, final LinkedList<LinkedList<String>> indexedKeys) {
        final long tableId = info.getId();
        final Map<String, Long> columnNameToId = info.columnNameToIdMap();
        final UUID rowId = UUID.randomUUID();
        final byte[] dataKey = RowKeyFactory.buildDataKey(tableId, rowId);
        final List<Put> putList = new LinkedList<Put>();
        final Put dataRow = createDataRows(dataKey, values, columnNameToId);

        if (values.size() == 0) {
            // Add special []->[] data row to signify a row of all null values
            putList.add(dataRow.add(Constants.NIC, new byte[0], new byte[0]));
        } else {
            putList.add(dataRow);
        }

        final byte[] rowByteArray = createRowFromMap(values);
        final Map<String, byte[]> ascendingValues = correctAscendingValuePadding(info, values);
        final Map<String, byte[]> descendingValues = correctDescendingValuePadding(info, values);

        for (List<String> columns : indexedKeys) {
            final byte[] columnIds = Index.createColumnIds(columns, columnNameToId);

            final byte[] ascendingIndexValues = Index.createValues(columns, ascendingValues);
            final byte[] descendingIndexValues = Index.createValues(columns, descendingValues);

            final byte[] ascendingIndexKey = RowKeyFactory.buildIndexKey(tableId, columnIds, ascendingIndexValues, rowId);
            final byte[] descendingIndexKey = RowKeyFactory.buildReverseIndexKey(tableId, columnIds, descendingIndexValues, rowId);

            putList.add(new Put(ascendingIndexKey).add(Constants.NIC, Constants.VALUE_MAP, rowByteArray));
            putList.add(new Put(descendingIndexKey).add(Constants.NIC, Constants.VALUE_MAP, rowByteArray));
        }

        return putList;
    }

    private static Put createDataRows(byte[] dataKey, Map<String, byte[]> values, Map<String, Long> columnNameToId) {
        final Put dataRow = new Put(dataKey);
        for (String columnName : values.keySet()) {
            final long columnId = columnNameToId.get(columnName);
            final byte[] value = values.get(columnName);
            dataRow.add(Constants.NIC, Bytes.toBytes(columnId), value);
        }

        return dataRow;
    }

    private static Map<String, byte[]> correctAscendingValuePadding(TableInfo info, Map<String, byte[]> values) {
        return convertToCorrectOrder(info, values, new Function<byte[], ColumnType, Integer, byte[]>() {
            @Override
            public byte[] apply(byte[] value, ColumnType columnType, Integer padLength) {
                return ValueEncoder.ascendingEncode(value, columnType, padLength);
            }
        });
    }

    private static Map<String, byte[]> correctDescendingValuePadding(TableInfo info, Map<String, byte[]> values) {
        return convertToCorrectOrder(info, values, new Function<byte[], ColumnType, Integer, byte[]>() {
            @Override
            public byte[] apply(byte[] value, ColumnType columnType, Integer padLength) {
                return ValueEncoder.descendingEncode(value, columnType, padLength);
            }
        });
    }

    private static Map<String, byte[]> convertToCorrectOrder(TableInfo info, Map<String, byte[]> values, Function<byte[], ColumnType, Integer, byte[]> convert) {
        ImmutableMap.Builder<String, byte[]> result = ImmutableMap.builder();
        for (String columnName : values.keySet()) {
            final ColumnType columnType = info.getColumnTypeByName(columnName);
            byte[] value = values.get(columnName);
            if (value == null) {
                value = new byte[1];  // TODO: Figure out how to get the null value.
                value[0] = 1;
            }

            int padLength = 0;
            if (columnType == ColumnType.STRING || columnType == ColumnType.BINARY) {
                final long maxLength = info.getColumnMetadata(columnName).getMaxLength();
                padLength = (int) maxLength - value.length;
            }

            byte[] paddedValue = convert.apply(value, columnType, padLength);
            result.put(columnName, paddedValue);
        }

        return result.build();
    }

    private static byte[] createRowFromMap(Map<String, byte[]> values) {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            final ObjectOutput out = new ObjectOutputStream(bos);
            out.writeObject(values);
            out.close();
        } catch (IOException e) {
            return new byte[0];
        }
        return bos.toByteArray();
    }

    private interface Function<F1, F2, F3, T> {
        T apply(F1 f1, F2 f2, F3 f3);
    }
}
