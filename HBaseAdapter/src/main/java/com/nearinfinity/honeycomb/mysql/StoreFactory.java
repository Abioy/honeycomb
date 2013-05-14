package com.nearinfinity.honeycomb.mysql;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Map;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.nearinfinity.honeycomb.Store;
import com.nearinfinity.honeycomb.config.AdapterType;
import com.nearinfinity.honeycomb.config.HoneycombConfiguration;

public class StoreFactory {
    private final Map<AdapterType, Provider<Store>> storeProviders;
    private final HoneycombConfiguration configuration;

    @Inject
    public StoreFactory(Map<AdapterType, Provider<Store>> storeMap,
                        HoneycombConfiguration configuration) {
        checkNotNull(storeMap);
        checkNotNull(configuration);

        storeProviders = storeMap;
        this.configuration = configuration;
    }

    /**
     * Returns a store implementation for a given table name.  Returns the store
     * matching the database name, or if that does not exist, the default adapter.
     * @param tableName
     * @return The store for the specified table name
     */
    public Store createStore(String tableName) {
        try {
            return storeProviders.get(AdapterType.valueOf(databaseName(tableName).toUpperCase())).get();
        } catch (IllegalArgumentException e) {
            return storeProviders.get(configuration.getDefaultAdapter()).get();
        }
    }

    private static String databaseName(String tableName) {
        return tableName.substring(0, tableName.indexOf("/"));
    }
}
