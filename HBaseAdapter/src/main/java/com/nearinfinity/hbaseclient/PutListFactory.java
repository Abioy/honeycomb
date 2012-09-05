package com.nearinfinity.hbaseclient;

import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PutListFactory {
    public static List<Put> createPutList(Map<String, byte[]> values, TableInfo info) throws IOException {
        //Get table id
        long tableId = info.getId();

        //Get UUID for new entry
        UUID rowId = UUID.randomUUID();

        //Build data row key
        byte[] dataKey = RowKeyFactory.buildDataKey(tableId, rowId);

        //Create put list
        List<Put> putList = new LinkedList<Put>();

        Put dataRow = new Put(dataKey);

        boolean allRowsNull = true;

        byte[] rowByteArray = createRowFromMap(values);

        for (String columnName : values.keySet()) {

            //Get column id and value
            long columnId = info.getColumnIdByName(columnName);
            ColumnType columnType = info.getColumnTypeByName(columnName);
            byte[] value = values.get(columnName);

            if (value == null) {
                // Build null index
                byte[] nullIndexRow = RowKeyFactory.buildNullIndexKey(tableId, columnId, rowId);
                putList.add(new Put(nullIndexRow).add(Constants.NIC, Constants.UNIREG, rowByteArray));
            } else {

                int padLength = 0;
                if (columnType == ColumnType.STRING || columnType == ColumnType.BINARY) {
                    byte[] maxLengthArray = info.getColumnMetadata(columnName, ColumnMetadata.MAX_LENGTH);
                    padLength = (int) ByteBuffer.wrap(maxLengthArray).getLong() - value.length;
                }

                allRowsNull = false;
                // Add data column to put
                dataRow.add(Constants.NIC, Bytes.toBytes(columnId), value);

                /**
                 * We need to get the canonical value for STRING types. The secondary index will store all values as
                 * canonical values. Then, when looking up in the primary index, the row key will contain the matching
                 * canonical value. The unireg and data rows store the actual value.
                 */
                byte[] canonicalValue = ValueEncoder.canonicalValue(value, columnType);

                // Build value index key
                byte[] indexRow = RowKeyFactory.buildValueIndexKey(tableId, columnId, canonicalValue, rowId);
                putList.add(new Put(indexRow).add(Constants.NIC, Constants.UNIREG, rowByteArray));

                // Build secondary index key
                byte[] secondaryIndexRow = RowKeyFactory.buildSecondaryIndexKey(tableId, columnId, canonicalValue, columnType);
                putList.add(new Put(secondaryIndexRow).add(Constants.NIC, Constants.VALUE_COLUMN, canonicalValue));

                // Build reverse index key
                byte[] reverseIndexRow = RowKeyFactory.buildReverseIndexKey(tableId, columnId, canonicalValue, columnType, padLength);
                putList.add(new Put(reverseIndexRow).add(Constants.NIC, Constants.VALUE_COLUMN, canonicalValue));
            }
        }

        if (allRowsNull) {
            // Add special []->[] data row to signify a row of all null values
            putList.add(dataRow.add(Constants.NIC, new byte[0], new byte[0]));
        }

        //Add the row to put list
        putList.add(dataRow);
        return putList;
    }

    private static byte[] createRowFromMap(Map<String, byte[]> values) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ObjectOutput out = new ObjectOutputStream(bos);
            out.writeObject(values);
            out.close();
        } catch (IOException e) {
            return new byte[0];
        }
        return bos.toByteArray();
    }
}
