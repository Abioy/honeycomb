package com.nearinfinity.honeycomb.mysql;

import com.google.common.collect.Maps;
import com.google.inject.Provider;
import com.nearinfinity.honeycomb.Store;
import com.nearinfinity.honeycomb.config.AdapterType;
import com.nearinfinity.honeycomb.config.HoneycombConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.powermock.api.mockito.PowerMockito.when;

public class StoreFactoryTest {
    @Mock
    Provider<Store> storeProvider;
    Store store;

    String tableName = "foo/bar";

    private static Map<String, String> hbaseConfigs = new HashMap<String, String>() {{
        put("option1", "value1");
        put("option2", "value2");
    }};

    private static Map<String, Map<String, String>> adapterConfigs = new HashMap<String, Map<String, String>>() {{
        put(AdapterType.HBASE.getName(), hbaseConfigs);
    }};

    @Before
    public void testSetup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testDefaultTablespaceIsUsed() {
        StoreFactory factory = createFactory();
        Store returnedStore = factory.createStore(tableName);
        assertEquals(returnedStore, this.store);
    }

    private StoreFactory createFactory() {
        HoneycombConfiguration configurationHolder = new HoneycombConfiguration(adapterConfigs, "hbase");
        Map<AdapterType, Provider<Store>> map = Maps.newHashMap();
        map.put(AdapterType.HBASE, storeProvider);
        when(storeProvider.get()).thenReturn(store);
        return new StoreFactory(map, configurationHolder);
    }
}
