package com.nearinfinity.hbaseclient.strategy;

import com.google.common.collect.Iterables;
import com.nearinfinity.hbaseclient.*;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;

import java.util.Map;

public class OrderedScanStrategy implements ScanStrategy {
    private final ScanStrategyInfo scanInfo;
    private final boolean indexFirst;

    public OrderedScanStrategy(ScanStrategyInfo scanInfo) {
        this(scanInfo, false);
    }

    public OrderedScanStrategy(ScanStrategyInfo scanInfo, boolean indexFirst) {
        this.scanInfo = scanInfo;
        this.indexFirst = indexFirst;
    }

    @Override
    public Scan getScan(TableInfo info) {
        long tableId = info.getId();
        Map<String, byte[]> ascendingValueMap = ValueEncoder.correctAscendingValuePadding(info, this.scanInfo.keyValueMap(), this.scanInfo.nullSearchColumns());
        Iterable<String> columns = this.scanInfo.columnNames();
        final int columnCount = Iterables.size(columns);

        byte[] columnIds = Index.createColumnIds(columns, info.columnNameToIdMap());
        byte[] nextColumnIds = Index.incrementColumn(columnIds, Bytes.SIZEOF_LONG * (columnCount - 1));

        int indexValuesFullLength = Index.calculateIndexValuesFullLength(columns, info);
        byte[] paddedValue = Index.createValues(this.scanInfo.keyValueColumns(), ascendingValueMap);
        paddedValue = Bytes.padTail(paddedValue, Math.max(indexValuesFullLength - paddedValue.length, 0));

        if (indexFirst) {
            paddedValue = new byte[paddedValue.length];
        }

        byte[] startKey = RowKeyFactory.buildIndexRowKey(tableId, columnIds, paddedValue, Constants.ZERO_UUID);
        byte[] endKey = RowKeyFactory.buildIndexRowKey(tableId, nextColumnIds, paddedValue, Constants.ZERO_UUID);

        return ScanFactory.buildScan(startKey, endKey);
    }

    @Override
    public String getTableName() {
        return this.scanInfo.tableName();
    }
}
