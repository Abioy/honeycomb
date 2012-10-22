package com.nearinfinity.hbaseclient;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.hadoop.hbase.client.Result;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public class ResultParser {
    public static UUID parseUUID(Result result) {
        byte[] rowKey = result.getRow();
        ByteBuffer byteBuffer = ByteBuffer.wrap(rowKey, rowKey.length - 16, 16);
        return new UUID(byteBuffer.getLong(), byteBuffer.getLong());
    }

    public static byte[] parseValueMap(Result result) {
        return result.getValue(Constants.NIC, Constants.VALUE_MAP);
    }

    @SuppressWarnings("unchecked")
    public static TreeMap<String, byte[]> parseRowMap(Result result) {
        byte[] mapBytes = parseValueMap(result);
        Gson gson = new Gson();
        Type type = new TypeToken<TreeMap<String, byte[]>>() {
        }.getType();
        return gson.fromJson(new String(mapBytes), type);
    }

    public static Map<String, byte[]> parseDataRow(Result result, TableInfo info) {
        //Get columns returned from Result
        Map<String, byte[]> columns = new HashMap<String, byte[]>();
        Map<byte[], byte[]> returnedColumns = result.getNoVersionMap().get(Constants.NIC);

        if (returnedColumns.size() == 1 && returnedColumns.containsKey(new byte[0])) {
            // The row of all nulls special case strikes again
            return columns;
        }

        //Loop through columns, add to returned map
        for (byte[] qualifier : returnedColumns.keySet()) {
            long columnId = ByteBuffer.wrap(qualifier).getLong();
            String columnName = info.getColumnNameById(columnId);
            columns.put(columnName, returnedColumns.get(qualifier));
        }

        return columns;
    }
}
