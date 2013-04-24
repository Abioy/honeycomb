package com.nearinfinity.honeycomb.mysql;

import static org.junit.Assert.assertEquals;
import static org.powermock.api.mockito.PowerMockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.google.inject.Provider;
import com.nearinfinity.honeycomb.Store;

public class StoreFactoryTest {
    @Mock
    Provider<Store> storeProvider;
    @Mock
    Store store;

    @Before
    public void testSetup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testDefaultTablespaceIsUsed() {
        String tablespace = "default";
        StoreFactory factory = createFactory(tablespace);
        Store store = factory.createStore();
        assertEquals(store, this.store);
    }

    private StoreFactory createFactory(String tablespace) {
        Map<String, Provider<Store>> map = new HashMap<String, Provider<Store>>();
        map.put(tablespace, storeProvider);
        when(storeProvider.get()).thenReturn(store);
        return new StoreFactory(map, tablespace);
    }
}
