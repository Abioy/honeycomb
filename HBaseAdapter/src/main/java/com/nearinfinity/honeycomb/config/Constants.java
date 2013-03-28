package com.nearinfinity.honeycomb.config;

import java.util.UUID;

public final class Constants {
    public static final byte[] DEFAULT_COLUMN_FAMILY = "nic".getBytes();

    public static final UUID ZERO_UUID = new UUID(0L, 0L);

    public static final UUID FULL_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    public static final String HBASE_TABLESPACE = "hbase";

    public static final String DEFAULT_TABLESPACE = "Default Tablespace";
}
