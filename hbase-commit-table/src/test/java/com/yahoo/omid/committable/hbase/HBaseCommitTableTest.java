package com.yahoo.omid.committable.hbase;

import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertEquals;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeClass;
import org.testng.Assert;
import static com.yahoo.omid.committable.hbase.HBaseCommitTable.COMMIT_TABLE_FAMILY;
import static com.yahoo.omid.committable.hbase.HBaseCommitTable.LOW_WATERMARK_FAMILY;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.coprocessor.AggregationClient;
import org.apache.hadoop.hbase.client.coprocessor.LongColumnInterpreter;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.yahoo.omid.committable.CommitTable.Client;
import com.yahoo.omid.committable.CommitTable.Writer;
import com.yahoo.omid.committable.hbase.HBaseCommitTable.HBaseClient;

public class HBaseCommitTableTest {

    private static final Logger LOG = LoggerFactory.getLogger(HBaseCommitTableTest.class);

    private static final String TEST_TABLE = "TEST";

    private static final TableName TABLE_NAME = TableName.valueOf(TEST_TABLE);

    private static HBaseTestingUtility testutil;
    private static MiniHBaseCluster hbasecluster;
    protected static Configuration hbaseConf;
    private static AggregationClient aggregationClient;

    @BeforeClass
    public static void setUpClass() throws Exception {
        // HBase setup
        hbaseConf = HBaseConfiguration.create();

        LOG.info("Create hbase");
        testutil = new HBaseTestingUtility(hbaseConf);
        hbasecluster = testutil.startMiniCluster(1);
        aggregationClient = new AggregationClient(hbaseConf);

    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (hbasecluster != null) {
            testutil.shutdownMiniCluster();
        }
    }

    @BeforeMethod
    public void setUp() throws Exception {
        HBaseAdmin admin = testutil.getHBaseAdmin();

        if (!admin.tableExists(TEST_TABLE)) {
            HTableDescriptor desc = new HTableDescriptor(TABLE_NAME);

            HColumnDescriptor datafam = new HColumnDescriptor(HBaseCommitTable.COMMIT_TABLE_FAMILY);
            datafam.setMaxVersions(Integer.MAX_VALUE);
            desc.addFamily(datafam);

            HColumnDescriptor lowWatermarkFam =
                    new HColumnDescriptor(HBaseCommitTable.LOW_WATERMARK_FAMILY);
            lowWatermarkFam.setMaxVersions(Integer.MAX_VALUE);
            desc.addFamily(lowWatermarkFam);

            desc.addCoprocessor("org.apache.hadoop.hbase.coprocessor.AggregateImplementation");
            admin.createTable(desc);
        }

        if (admin.isTableDisabled(TEST_TABLE)) {
            admin.enableTable(TEST_TABLE);
        }
        HTableDescriptor[] tables = admin.listTables();
        for (HTableDescriptor t : tables) {
            LOG.info(t.getNameAsString());
        }
    }

    @AfterMethod
    public void tearDown() {
        try {
            LOG.info("tearing Down");
            HBaseAdmin admin = testutil.getHBaseAdmin();
            admin.disableTable(TEST_TABLE);
            admin.deleteTable(TEST_TABLE);

        } catch (Exception e) {
            LOG.error("Error tearing down", e);
        }
    }

    @Test
    public void testCreateTable() throws Throwable {
        HBaseAdmin admin = testutil.getHBaseAdmin();

        String tableName = "Table_with_single_region";
        CreateTable.createTable(hbaseConf, tableName, 1);
        int numRegions = admin.getTableRegions(Bytes.toBytes(tableName)).size();
        assertEquals("Should have only 1 region", 1, numRegions);

        tableName = "Table_with_multiple_region";
        CreateTable.createTable(hbaseConf, tableName, 60);
        numRegions = admin.getTableRegions(Bytes.toBytes(tableName)).size();
        assertEquals("Should have 60 regions", 60, numRegions);
    }

    @Test
    public void testBasicBehaviour() throws Throwable {
        HBaseCommitTableConfig config = new HBaseCommitTableConfig();
        config.setTableName(TEST_TABLE);
        HBaseCommitTable commitTable = new HBaseCommitTable(hbaseConf, config);

        ListenableFuture<Writer> futureWriter = commitTable.getWriter();
        Writer writer = futureWriter.get();
        ListenableFuture<Client> futureClient = commitTable.getClient();
        Client client = futureClient.get();

        // Test that the first time the table is empty
        assertEquals("Rows should be 0!", 0, rowCount(TABLE_NAME, COMMIT_TABLE_FAMILY));

        // Test the successful creation of 1000 txs in the table
        for (int i = 0; i < 1000; i++) {
            writer.addCommittedTransaction(i, i + 1);
        }
        writer.flush().get();
        assertEquals("Rows should be 1000!", 1000, rowCount(TABLE_NAME, COMMIT_TABLE_FAMILY));

        // Test the we get the right commit timestamps for each previously inserted tx
        for (long i = 0; i < 1000; i++) {
            ListenableFuture<Optional<Long>> ctf = client.getCommitTimestamp(i);
            Optional<Long> optional = ctf.get();
            Long ct = optional.get();
            assertEquals("Commit timestamp should be " + (i + 1), (i + 1), (long) ct);
        }
        assertEquals("Rows should be 1000!", 1000, rowCount(TABLE_NAME, COMMIT_TABLE_FAMILY));

        // Test the successful deletion of the 1000 txs
        Future<Void> f = null;
        for (long i = 0; i < 1000; i++) {
            f = client.completeTransaction(i);
            f.get();
        }
        assertEquals("Rows should be 0!", 0, rowCount(TABLE_NAME, COMMIT_TABLE_FAMILY));

        // Test we don't get a commit timestamp for a non-existent transaction id in the table
        ListenableFuture<Optional<Long>> ctf = client.getCommitTimestamp(0);
        Optional<Long> optional = ctf.get();
        assertFalse("Commit timestamp should not be present", optional.isPresent());

        // Test that the first time, the low watermark family in table is empty
        assertEquals("Rows should be 0!", 0, rowCount(TABLE_NAME, LOW_WATERMARK_FAMILY));

        // Test the unsuccessful read of the low watermark the first time
        ListenableFuture<Long> lowWatermarkFuture = client.readLowWatermark();
        assertEquals("Low watermark should be 0", Long.valueOf(0), lowWatermarkFuture.get());

        // Test the successful update of the low watermark
        for (int lowWatermark = 0; lowWatermark < 1000; lowWatermark++) {
            writer.updateLowWatermark(lowWatermark);
        }
        writer.flush().get();
        assertEquals("Should there be only one row!", 1, rowCount(TABLE_NAME, LOW_WATERMARK_FAMILY));

        // Test the successful read of the low watermark
        lowWatermarkFuture = client.readLowWatermark();
        long lowWatermark = lowWatermarkFuture.get();
        assertEquals("Low watermark should be 999", 999, lowWatermark);
        assertEquals("Should there be only one row!", 1, rowCount(TABLE_NAME, LOW_WATERMARK_FAMILY));

    }

    @Test
    public void testClosingClientEmptyQueuesProperly() throws Throwable {
        HBaseCommitTableConfig config = new HBaseCommitTableConfig();
        config.setTableName(TEST_TABLE);
        HBaseCommitTable commitTable = new HBaseCommitTable(hbaseConf, config);

        ListenableFuture<Writer> futureWriter = commitTable.getWriter();
        Writer writer = futureWriter.get();
        ListenableFuture<Client> futureClient = commitTable.getClient();
        HBaseCommitTable.HBaseClient client = (HBaseClient) futureClient.get();

        for (int i = 0; i < 1000; i++) {
            writer.addCommittedTransaction(i, i + 1);
        }
        writer.flush().get();

        // Completing first transaction should be fine
        client.completeTransaction(0).get();
        assertEquals("Rows should be 999!", 999, rowCount(TABLE_NAME, COMMIT_TABLE_FAMILY));

        // When closing, removing a transaction should throw an EE with an IOException
        client.close();
        try {
            client.completeTransaction(1).get();
            Assert.fail();
        } catch (ExecutionException e) {
            // Expected
        }
        assertEquals("Delete queue size should be 0!", 0, client.deleteQueue.size());
        assertEquals("Rows should be 999!", 999, rowCount(TABLE_NAME, COMMIT_TABLE_FAMILY));

    }

    private static long rowCount(TableName table, byte[] family) throws Throwable {
        Scan scan = new Scan();
        scan.addFamily(family);
        return aggregationClient.rowCount(table, new LongColumnInterpreter(), scan);
    }

}
