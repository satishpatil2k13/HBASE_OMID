package com.yahoo.omid.transaction;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;
import org.testng.annotations.Test;
import org.testng.Assert;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.BinaryPrefixComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.filter.WhileMatchFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yahoo.omid.transaction.TTable;
import com.yahoo.omid.transaction.Transaction;
import com.yahoo.omid.transaction.TransactionManager;

public class TestUpdateScan extends OmidTestBase {
    private static final Logger LOG = LoggerFactory.getLogger(TestUpdateScan.class);

    private static final String TEST_COL = "value";
    private static final String TEST_COL_2 = "col_2";

    @Test
    public void testGet() throws Exception {
        try{
            TransactionManager tm = newTransactionManager();
            TTable table = new TTable(hbaseConf, TEST_TABLE);
            Transaction t=tm.begin();
            int[] lInts=new int[]{100,243,2342,22,1,5,43,56};
            for (int i=0;i<lInts.length;i++) {
                byte[]data=Bytes.toBytes(lInts[i]);
                Put put=new Put(data);
                put.add(Bytes.toBytes(TEST_FAMILY), Bytes.toBytes(TEST_COL), data);
                table.put(t,put);
            }
            int startKeyValue=lInts[3];
            int stopKeyValue=lInts[3];
            byte[] startKey=Bytes.toBytes(startKeyValue);
            byte[] stopKey=Bytes.toBytes(stopKeyValue);
            Get g=new Get(startKey);
            Result r=table.get(t,g);
            if (!r.isEmpty()) {
                int tmp=Bytes.toInt(r.getValue(Bytes.toBytes(TEST_FAMILY), 
                                               Bytes.toBytes(TEST_COL)));
                LOG.info("Result:" + tmp);
                assertTrue("Bad value, should be " 
                           + startKeyValue + " but is " + tmp 
                           , tmp == startKeyValue);
            } else {
                Assert.fail("Bad result");
            }
            tm.commit(t);

            Scan s=new Scan(startKey);
            CompareFilter.CompareOp op=CompareFilter.CompareOp.LESS_OR_EQUAL;
            RowFilter toFilter = new RowFilter(op, new BinaryPrefixComparator(stopKey));
            boolean startInclusive=true;
            if (!startInclusive)  {
                FilterList filters = new FilterList(FilterList.Operator.MUST_PASS_ALL);
                filters.addFilter(new RowFilter(CompareFilter.CompareOp.GREATER, 
                                                new BinaryPrefixComparator(startKey)));
                filters.addFilter(new WhileMatchFilter(toFilter));
                s.setFilter(filters);
            } else {
                s.setFilter(new WhileMatchFilter(toFilter));
            }
            t=tm.begin();
            ResultScanner res=table.getScanner(t,s);
            Result rr;
            int count = 0;
            while ((rr=res.next())!=null) {
                int iTmp=Bytes.toInt(rr.getValue(Bytes.toBytes(TEST_FAMILY), 
                                                 Bytes.toBytes(TEST_COL)));
                LOG.info("Result: "+iTmp);
                count++;
            }
            assertEquals("Count is wrong", 1, count);
            LOG.info("Rows found " + count);
            tm.commit(t);
            table.close();
        } catch (Exception e) {
            LOG.error("Exception in test", e);
        }
    }

    @Test
    public void testScan() throws Exception {

        try (TTable table = new TTable(hbaseConf, TEST_TABLE)) {
            TransactionManager tm = newTransactionManager();
            Transaction t = tm.begin();
            int[] lInts = new int[] { 100, 243, 2342, 22, 1, 5, 43, 56 };
            for (int i = 0; i < lInts.length; i++) {
                byte[] data = Bytes.toBytes(lInts[i]);
                Put put = new Put(data);
                put.add(Bytes.toBytes(TEST_FAMILY), Bytes.toBytes(TEST_COL), data);
                put.add(Bytes.toBytes(TEST_FAMILY), Bytes.toBytes(TEST_COL_2), data);
                table.put(t, put);
            }

            Scan s = new Scan();
            // Adding two columns to the scanner should not throw a
            // ConcurrentModificationException when getting the scanner
            s.addColumn(Bytes.toBytes(TEST_FAMILY), Bytes.toBytes(TEST_COL));
            s.addColumn(Bytes.toBytes(TEST_FAMILY), Bytes.toBytes(TEST_COL_2));
            ResultScanner res = table.getScanner(t, s);
            Result rr;
            int count = 0;
            while ((rr = res.next()) != null) {
                int iTmp = Bytes.toInt(rr.getValue(Bytes.toBytes(TEST_FAMILY),
                        Bytes.toBytes(TEST_COL)));
                LOG.info("Result: " + iTmp);
                count++;
            }
            assertTrue("Count should be " + lInts.length + " but is " + count,
                    count == lInts.length);
            LOG.info("Rows found " + count);

            tm.commit(t);

            t = tm.begin();
            res = table.getScanner(t, s);
            count = 0;
            while ((rr = res.next()) != null) {
                int iTmp = Bytes.toInt(rr.getValue(Bytes.toBytes(TEST_FAMILY),
                        Bytes.toBytes(TEST_COL)));
                LOG.info("Result: " + iTmp);
                count++;
            }
            assertTrue("Count should be " + lInts.length + " but is " + count,
                    count == lInts.length);
            LOG.info("Rows found " + count);
            tm.commit(t);
        }

    }
   

    @Test 
    public void testScanUncommitted() throws Exception {
        try{
            TransactionManager tm = newTransactionManager();
            TTable table = new TTable(hbaseConf, TEST_TABLE);
            Transaction t=tm.begin();
            int[] lIntsA=new int[]{100,243,2342,22,1,5,43,56};
            for (int i=0;i<lIntsA.length;i++) {
                byte[]data=Bytes.toBytes(lIntsA[i]);
                Put put=new Put(data);
                put.add(Bytes.toBytes(TEST_FAMILY), Bytes.toBytes(TEST_COL), data);
                table.put(t,put);
            }
            tm.commit(t);
   
            Transaction tu=tm.begin();
            int[] lIntsB=new int[]{105,24,4342,32,7,3,30,40};
            for (int i=0;i<lIntsB.length;i++) {
                byte[]data=Bytes.toBytes(lIntsB[i]);
                Put put=new Put(data);
                put.add(Bytes.toBytes(TEST_FAMILY), Bytes.toBytes(TEST_COL), data);
                table.put(tu,put);
            }
   
            t=tm.begin();
            int[] lIntsC=new int[]{109,224,242,2,16,59,23,26};
            for (int i=0;i<lIntsC.length;i++) {
                byte[]data=Bytes.toBytes(lIntsC[i]);
                Put put=new Put(data);
                put.add(Bytes.toBytes(TEST_FAMILY), Bytes.toBytes(TEST_COL), data);
                table.put(t,put);
            }
            tm.commit(t);
         
            t=tm.begin();
            Scan s=new Scan();
            ResultScanner res=table.getScanner(t,s);
            Result rr;
            int count = 0;
   
            while ((rr=res.next())!=null) {
                int iTmp=Bytes.toInt(rr.getValue(Bytes.toBytes(TEST_FAMILY), 
                                                 Bytes.toBytes(TEST_COL)));
                LOG.info("Result: "+iTmp);
                count++;
            }
            assertTrue("Count should be " + (lIntsA.length*lIntsC.length) + " but is " + count, 
                       count == lIntsA.length + lIntsC.length);
            LOG.info("Rows found " + count);
            tm.commit(t);
            table.close();
        } catch (Exception e) {
            LOG.error("Exception in test", e);
        }
    }  
}
