package com.splicemachine.derby.impl.load;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.splicemachine.derby.test.DerbyTestRule;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;
import org.junit.*;

import java.sql.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


public class HdfsImportTest {
	private static final Logger LOG = Logger.getLogger(HdfsImportTest.class);
	
	static final Map<String,String> tableSchemaMap = Maps.newHashMap();
	static{
		tableSchemaMap.put("t","name varchar(40), title varchar(40), age int");
		tableSchemaMap.put("order_detail","order_id VARCHAR(50), item_id INT, order_amt INT,order_date TIMESTAMP, emp_id INT, " +
				"promotion_id INT, qty_sold INT, unit_price FLOAT, unit_cost FLOAT, discount FLOAT, customer_id INT");
	}
	
	@Rule public DerbyTestRule rule = new DerbyTestRule(tableSchemaMap,LOG);

	@BeforeClass
	public static void start() throws Exception{
		DerbyTestRule.start();
	}
	
	@AfterClass
	public static void shutdown() throws Exception {
		DerbyTestRule.shutdown();
	}

	@Test
//    @Ignore
	public void testHdfsImport() throws Exception{
		String baseDir = System.getProperty("user.dir");
		String location = baseDir+"/structured_derby/src/test/resources/importTest.in";
		testImport(location,"NAME,TITLE,AGE");
	}

	private void testImport(String location,String colList) throws SQLException {
		HdfsImport.importData(rule.getConnection(), null, "T", colList, location, ",","\"");

		ResultSet rs = rule.executeQuery("select * from t");
		List<String> results = Lists.newArrayList();
		while(rs.next()){
			String name = rs.getString(1);
			String title = rs.getString(2);
			Integer age = rs.getInt(3);
			Assert.assertNotNull("Name is null!", name);
			Assert.assertNotNull("Title is null!", title);
			Assert.assertNotNull("Age is null!",age);
			results.add(String.format("name:%s,title:%s,age:%d",name,title,age));
		}
		for(String result:results){
			LOG.info(result);
		}
		Assert.assertTrue("no rows imported!",results.size()>0);
	}

	@Test
	public void testHdfsImportGzipFile() throws Exception{
		String location = System.getProperty("user.dir")+"/structured_derby/src/test/resources/importTest.in.gz";
		testImport(location,"NAME,TITLE,AGE");
	}

	@Test
	public void testImportFromSQL() throws Exception{
		String location = System.getProperty("user.dir")+"structured_derby/src/test/resources/order_detail_small.csv";
		PreparedStatement ps = rule.prepareStatement("call SYSCS_UTIL.SYSCS_IMPORT_DATA (null,'ORDER_DETAIL',null,null," +
				"'"+location+"',',',null,null)");
		ps.execute();

		ResultSet rs = rule.executeQuery("select * from order_detail");
		List<String> results = Lists.newArrayList();
		while(rs.next()){
			String orderId = rs.getString(1);
			int item_id = rs.getInt(2);
			int order_amt = rs.getInt(3);
			Timestamp order_date = rs.getTimestamp(4);
			int emp_id = rs.getInt(5);
			int prom_id = rs.getInt(6);
			int qty_sold = rs.getInt(7);
			float unit_price = rs.getInt(8);
			float unit_cost = rs.getFloat(9);
			float discount = rs.getFloat(10);
			int cust_id = rs.getInt(11);
			Assert.assertNotNull("No Order Id returned!",orderId);
			Assert.assertTrue("ItemId incorrect!",item_id>0);
			Assert.assertTrue("Order amt incorrect!",order_amt>0);
			Assert.assertNotNull("order_date incorrect",order_date);
			Assert.assertTrue("EmpId incorrect",emp_id>0);
			Assert.assertEquals("prom_id incorrect",0,prom_id);
			Assert.assertTrue("qty_sold incorrect",qty_sold>0);
			Assert.assertTrue("unit price incorrect!",unit_price>0);
			Assert.assertTrue("unit cost incorrect",unit_cost>0);
			Assert.assertEquals("discount incorrect",0.0f,discount,1/100f);
			Assert.assertTrue("cust_id incorrect",cust_id!=0);
			results.add(String.format("orderId:%s,item_id:%d,order_amt:%d,order_date:%s,emp_id:%d,prom_id:%d,qty_sold:%d,unit_price:%f,unit_cost:%f,discount:%f,cust_id:%d",orderId,item_id,order_amt,order_date,emp_id,prom_id,qty_sold,unit_price,unit_cost,discount,cust_id));
		}
		for(String result:results){
			LOG.info(result);
		}

		Assert.assertTrue("import failed!",results.size()>0);
	}

	@Test
	public void testHdfsImportNullColList() throws Exception{
		String baseDir = System.getProperty("user.dir");
		String location = baseDir+"/structured_derby/src/test/resources/importTest.in";
		testImport(location,null);
	}
	
	@Test
	public void testCallScript() throws Exception{
		ResultSet rs = rule.getConnection().getMetaData().getColumns(null, "SYS","SYSSCHEMAS",null);
		Map<String,Integer>colNameToTypeMap = Maps.newHashMap();
		colNameToTypeMap.put("SCHEMAID",Types.CHAR);
		colNameToTypeMap.put("SCHEMANAME",Types.VARCHAR);
		colNameToTypeMap.put("AUTHORIZATIONID",Types.VARCHAR);
		try{
			int count=0;
			while(rs.next()){
				String colName = rs.getString(4);
				int  colType = rs.getInt(5);
				Assert.assertTrue("ColName not contained in map: "+ colName,
												colNameToTypeMap.containsKey(colName));
				Assert.assertEquals("colType incorrect!",
									colNameToTypeMap.get(colName).intValue(),colType);
				count++;
			}
			Assert.assertEquals("incorrect count returned!",colNameToTypeMap.size(),count);
		}finally{
			if(rs!=null)rs.close();
		}
	}

    @Test
    public void testCallWithRestrictions() throws Exception{
        PreparedStatement ps = rule.prepareStatement("select schemaname,schemaid from sys.sysschemas where schemaname like ?");
        ps.setString(1,"SYS");
        ResultSet rs = ps.executeQuery();//rule.executeQuery("select schemaname,schemaid from sys.sysschemas where schemaname like 'SYS'");
        while(rs.next()){
            LOG.info("schemaid="+rs.getString(1)+",schemaname="+rs.getString(2));
//            LOG.info("schemaname="+rs.getString(1));
        }
    }



    @Test
    public void testDataIsAvailable() throws Exception{
        long conglomId = 352;
        ResultSet rs = rule.executeQuery("select * from sys.sysconglomerates");
        while(rs.next()){
            String tableId = rs.getString(2);
            long tconglomId = rs.getLong(3);
            LOG.info("tableId="+tableId+",conglomid="+tconglomId);
            if(tconglomId==conglomId){
                LOG.warn("getting table name for conglomid "+ conglomId);
	            rs.close();
	            rs = rule.executeQuery("select tablename,tableid from sys.systables");
                while(rs.next()){
                    if(tableId.equals(rs.getString(2))){
                        LOG.warn("ConglomeId="+conglomId+",tableName="+rs.getString(1));
                        break;
                    }
                }
                break;
            }
        }
        LOG.error("Bytes.toBytes(SYS)="+ Arrays.toString(Bytes.toBytes("SYS")));
    }
	
}
