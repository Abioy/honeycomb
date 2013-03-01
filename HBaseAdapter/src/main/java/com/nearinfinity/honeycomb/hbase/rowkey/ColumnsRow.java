package com.nearinfinity.honeycomb.hbase.rowkey;

import com.google.common.base.Preconditions;
import com.nearinfinity.honeycomb.hbase.RowKey;
import com.nearinfinity.honeycomb.hbase.VarEncoder;

public class ColumnsRow implements RowKey {
    private static final byte PREFIX = 0x01;

    private long tableId;

    public ColumnsRow(long tableId) {
        Preconditions.checkArgument(tableId >= 0, "Table ID must be non-zero.");
        this.tableId = tableId;
    }

    public byte[] encode() {
        byte[] table = VarEncoder.encodeULong(tableId);
        byte[] ret = VarEncoder.appendByteArraysWithPrefix(PREFIX, table);
        return ret;
    }

    public byte getPrefix() {
        return PREFIX;
    }

    public long getTableId() {
        return tableId;
    }

    @Override
    public String toString() {
        return "[" + String.format("%02X", PREFIX) + "\t" + tableId + "]";
    }
}