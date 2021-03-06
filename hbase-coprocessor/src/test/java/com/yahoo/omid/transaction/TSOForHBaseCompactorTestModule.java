package com.yahoo.omid.transaction;

import static com.yahoo.omid.tso.RequestProcessorImpl.TSO_MAX_ITEMS_KEY;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import javax.inject.Singleton;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import com.yahoo.omid.committable.CommitTable;
import com.yahoo.omid.committable.hbase.HBaseCommitTable;
import com.yahoo.omid.metrics.MetricsRegistry;
import com.yahoo.omid.metrics.NullMetricsProvider;
import com.yahoo.omid.timestamp.storage.TimestampStorage;
import com.yahoo.omid.tso.DisruptorModule;
import com.yahoo.omid.tso.MockPanicker;
import com.yahoo.omid.tso.Panicker;
import com.yahoo.omid.tso.TSOServerCommandLineConfig;
import com.yahoo.omid.tso.TimestampOracle;
import com.yahoo.omid.tso.TimestampOracleImpl;
import com.yahoo.omid.tso.ZKModule;
import com.yahoo.omid.tso.hbase.HBaseTimestampStorage;

public class TSOForHBaseCompactorTestModule extends AbstractModule {

    private static final Logger LOG = LoggerFactory.getLogger(TSOForHBaseCompactorTestModule.class);

    private final TSOServerCommandLineConfig config;

    public TSOForHBaseCompactorTestModule(TSOServerCommandLineConfig config) {
        this.config = config;
    }

    @Override
    protected void configure() {

        bind(Panicker.class).to(MockPanicker.class);
        // HBase commit table creation
        bind(CommitTable.class).to(HBaseCommitTable.class).in(Singleton.class);
        // Timestamp storage creation
        bind(TimestampStorage.class).to(HBaseTimestampStorage.class).in(Singleton.class);
        bind(TimestampOracle.class).to(TimestampOracleImpl.class).in(Singleton.class);

        // DisruptorConfig
        // Overrite default value
        bindConstant().annotatedWith(Names.named(TSO_MAX_ITEMS_KEY)).to(config.getMaxItems());
        LOG.info("Overriding {} with value {}", TSO_MAX_ITEMS_KEY, config.getMaxItems());
        install(new DisruptorModule());

        // ZK Module
        install(new ZKModule(config));

    }

    @Provides
    @Singleton
    Configuration provideHBaseConfig() throws IOException {
        Configuration hbaseConf = HBaseConfiguration.create();
        hbaseConf.setInt("hbase.hregion.memstore.flush.size", 10000 * 1024);
        hbaseConf.setInt("hbase.regionserver.nbreservationblocks", 1);
        hbaseConf.set("tso.host", "localhost");
        hbaseConf.setInt("tso.port", 1234);
        hbaseConf.set("hbase.coprocessor.region.classes", "com.yahoo.omid.transaction.OmidCompactor");
        final String rootdir = "/tmp/hbase.test.dir/";
        File rootdirFile = new File(rootdir);
        if (rootdirFile.exists()) {
            delete(rootdirFile);
        }
        hbaseConf.set("hbase.rootdir", rootdir);
        return hbaseConf;
    }

    @Provides
    TSOServerCommandLineConfig provideTSOServerConfig() {
        return config;
    }

    @Provides
    @Singleton
    MetricsRegistry provideMetricsRegistry() {
        return new NullMetricsProvider();
    }

    private static void delete(File f) throws IOException {
        if (f.isDirectory()) {
            for (File c : f.listFiles())
                delete(c);
        }
        if (!f.delete())
            throw new FileNotFoundException("Failed to delete file: " + f);
    }

}