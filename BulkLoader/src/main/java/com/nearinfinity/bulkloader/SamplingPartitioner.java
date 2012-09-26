package com.nearinfinity.bulkloader;


import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.Partitioner;

public class SamplingPartitioner extends Partitioner<ImmutableBytesWritable, Put> implements Configurable {

    public static final String COLUMN_COUNT = "mapreduce.samplepartitioner.columncount";

    private int columnCount;

    private Configuration conf;

    @Override
    public int getPartition(ImmutableBytesWritable immutableBytesWritable, Put put, int numPartitions) {
        byte[] row = immutableBytesWritable.get();
        byte rowKey = row[0];
        switch (rowKey) {
            case 3:
                return 0;
            case 4:
                int indexRow = (1 + (int) Bytes.toLong(row, 9, 8)) % numPartitions;
                return indexRow;
            case 5:
                int reverseIndexRow = (1 + columnCount + (int) Bytes.toLong(row, 9, 8)) % numPartitions;
                return reverseIndexRow;
        }

        return 0;
    }

    public static void setColumnCount(Configuration conf, int columnCount) {
        conf.setInt(COLUMN_COUNT, columnCount);
    }

    @Override
    public void setConf(Configuration conf) {
        this.conf = conf;
        this.columnCount = conf.getInt(COLUMN_COUNT, 3);
    }

    @Override
    public Configuration getConf() {
        return this.conf;
    }
}
