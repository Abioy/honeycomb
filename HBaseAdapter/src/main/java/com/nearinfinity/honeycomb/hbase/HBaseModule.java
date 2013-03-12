package com.nearinfinity.honeycomb.hbase;

import com.google.inject.AbstractModule;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.multibindings.MapBinder;
import com.nearinfinity.honeycomb.Store;
import com.nearinfinity.honeycomb.Table;
import com.nearinfinity.honeycomb.hbaseclient.Constants;
import com.nearinfinity.honeycomb.hbaseclient.SqlTableCreator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.log4j.Logger;

import java.io.IOException;

public class HBaseModule extends AbstractModule {
    private static final Logger logger = Logger.getLogger(HBaseModule.class);
    private final HTableProvider hTableProvider;
    private final MapBinder<String, Store> storeMapBinder;

    public HBaseModule(Configuration configuration, MapBinder<String, Store> storeMapBinder) throws IOException {
        try {
            this.storeMapBinder = storeMapBinder;
            this.hTableProvider = new HTableProvider(configuration);

            String hTableName = configuration.get(Constants.HBASE_TABLE);
            String zkQuorum = configuration.get(Constants.ZK_QUORUM);
            Configuration hBaseConfiguration = HBaseConfiguration.create();
            hBaseConfiguration.set("hbase.zookeeper.quorum", zkQuorum);
            hBaseConfiguration.set(Constants.HBASE_TABLE, hTableName);
            SqlTableCreator.initializeSqlTable(hBaseConfiguration);
        } catch (ZooKeeperConnectionException e) {
            logger.fatal("Could not connect to zookeeper. ", e);
            throw e;
        } catch (IOException e) {
            logger.fatal("Could not create HBaseStore. Aborting initialization.");
            throw e;
        }
    }

    @Override
    protected void configure() {
        storeMapBinder.addBinding("hbase").to(HBaseStore.class);
        install(new FactoryModuleBuilder()
                .implement(Table.class, HBaseTable.class)
                .build(HBaseTableFactory.class));

        bind(new TypeLiteral<Provider<HTableInterface>>() {
        }).toInstance(this.hTableProvider);

        bind(HTableInterface.class).toProvider(this.hTableProvider);
    }
}
