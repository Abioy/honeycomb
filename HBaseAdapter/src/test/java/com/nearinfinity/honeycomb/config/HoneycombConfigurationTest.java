package com.nearinfinity.honeycomb.config;

import com.nearinfinity.honeycomb.hbase.config.ConfigConstants;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class HoneycombConfigurationTest {

    private static Map<String, String> hbaseConfigs = new HashMap<String, String>() {{
        put(ConfigConstants.TABLE_NAME, "sql");
        put(ConfigConstants.COLUMN_FAMILY, "nic");
    }};

    private static Map<String, Map<String, String>> adapterConfigs = new HashMap<String, Map<String, String>>() {{
        put(AdapterType.HBASE.getName(), hbaseConfigs);
    }};

    private HoneycombConfiguration configuration;

    @Before
    public void setupTests() {
        configuration = new HoneycombConfiguration(adapterConfigs, "hbase");
    }

    @Test
    public void testIsAdapterConfigured() throws Exception {
        Assert.assertTrue(configuration.isAdapterConfigured(AdapterType.HBASE));
    }

    @Test
    public void testIsAdapterNotConfigured() throws Exception {
        Assert.assertFalse(configuration.isAdapterConfigured(AdapterType.MEMORY));
    }

    @Test
    public void testGetAdapterOptions() throws Exception {
        Assert.assertEquals(hbaseConfigs, configuration.getAdapterOptions(AdapterType.HBASE));
    }
}
