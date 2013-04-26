package com.nearinfinity.honeycomb.config;

/**
 * Stores the name of the configuration option tags in honeycomb.xml
 */
public final class ConfigConstants {

    private ConfigConstants() {
        throw new AssertionError();
    }

    private static final String NAMESPACE = Constants.HONEYCOMB_NAMESPACE + "." + StoreType.HBASE.getName() + ".";

    public static final String AUTO_FLUSH = NAMESPACE + "flushChangesImmediately";

    public static final boolean DEFAULT_AUTO_FLUSH = false;

    public static final String TABLE_POOL_SIZE = NAMESPACE + "tablePoolSize";

    /**
     * Default number of references to keep active for a table
     */
    public static final int DEFAULT_TABLE_POOL_SIZE = 5;

    public static final String TABLE_NAME = NAMESPACE + "tableName";

    public static final String COLUMN_FAMILY = NAMESPACE + "columnFamily";

    public static final String DEFAULT_COLUMN_FAMILY = NAMESPACE + "nic";

    public static final String WRITE_BUFFER = "hbase.client.write.buffer";

    public static final long DEFAULT_WRITE_BUFFER = 2097152;
}
