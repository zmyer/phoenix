/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end;

import static org.apache.phoenix.util.PhoenixRuntime.TENANT_ID_ATTRIB;
import static org.apache.phoenix.util.PhoenixRuntime.UPSERT_BATCH_SIZE_ATTRIB;
import static org.apache.phoenix.util.TestUtil.ATABLE_NAME;
import static org.apache.phoenix.util.TestUtil.A_VALUE;
import static org.apache.phoenix.util.TestUtil.B_VALUE;
import static org.apache.phoenix.util.TestUtil.CUSTOM_ENTITY_DATA_FULL_NAME;
import static org.apache.phoenix.util.TestUtil.C_VALUE;
import static org.apache.phoenix.util.TestUtil.PTSDB_NAME;
import static org.apache.phoenix.util.TestUtil.ROW6;
import static org.apache.phoenix.util.TestUtil.ROW7;
import static org.apache.phoenix.util.TestUtil.ROW8;
import static org.apache.phoenix.util.TestUtil.ROW9;
import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.Test;


public class UpsertSelectIT extends ParallelStatsDisabledIT {
	
	
  @BeforeClass
  @Shadower(classBeingShadowed = ParallelStatsDisabledIT.class)
  public static void doSetup() throws Exception {
      Map<String,String> props = new HashMap<>();
      props.put(QueryServices.QUEUE_SIZE_ATTRIB, Integer.toString(500));
      props.put(QueryServices.THREAD_POOL_SIZE_ATTRIB, Integer.toString(64));

      // Must update config before starting server
      setUpTestDriver(new ReadOnlyProps(props.entrySet().iterator()));
  }
    
    @Test
    public void testUpsertSelectWithNoIndex() throws Exception {
        testUpsertSelect(false, false);
    }
    
    @Test
    public void testUpsertSelecWithIndex() throws Exception {
        testUpsertSelect(true, false);
    }
    
    @Test
    public void testUpsertSelecWithIndexWithSalt() throws Exception {
        testUpsertSelect(true, true);
    }

    @Test
    public void testUpsertSelecWithNoIndexWithSalt() throws Exception {
        testUpsertSelect(false, true);
    }

    private void testUpsertSelect(boolean createIndex, boolean saltTable) throws Exception {
        long ts = nextTimestamp();
        String tenantId = getOrganizationId();
        byte[][] splits = getDefaultSplits(tenantId);
        String aTable = initATableValues(tenantId, saltTable ? null : splits, null, ts-1, getUrl(), saltTable ? "salt_buckets = 2" : null);

        String customEntityTable = generateUniqueName();
        ensureTableCreated(getUrl(), customEntityTable, CUSTOM_ENTITY_DATA_FULL_NAME, null, ts-1, saltTable ? "salt_buckets = 2" : null);
        String indexName = generateUniqueName();
        if (createIndex) {
            Properties props = new Properties();
            props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts)); // Execute at timestamp 1
            Connection conn = DriverManager.getConnection(getUrl(), props);
            conn.createStatement().execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + aTable + "(a_string)" );
            conn.close();
        }
        PreparedStatement upsertStmt;
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 2)); // Execute at timestamp 2
        props.setProperty(UPSERT_BATCH_SIZE_ATTRIB, Integer.toString(3)); // Trigger multiple batches
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true);
        String upsert = "UPSERT INTO " + customEntityTable + "(custom_entity_data_id, key_prefix, organization_id, created_by) " +
            "SELECT substr(entity_id, 4), substr(entity_id, 1, 3), organization_id, a_string  FROM " + aTable + " WHERE ?=a_string";
        if (createIndex) { // Confirm index is used
            upsertStmt = conn.prepareStatement("EXPLAIN " + upsert);
            upsertStmt.setString(1, tenantId);
            ResultSet ers = upsertStmt.executeQuery();
            assertTrue(ers.next());
            String explainPlan = QueryUtil.getExplainPlan(ers);
            assertTrue(explainPlan.contains(" SCAN OVER " + indexName));
        }
        
        upsertStmt = conn.prepareStatement(upsert);
        upsertStmt.setString(1, A_VALUE);
        int rowsInserted = upsertStmt.executeUpdate();
        assertEquals(4, rowsInserted);
        conn.commit();
        conn.close();
        
        String query = "SELECT key_prefix, substr(custom_entity_data_id, 1, 1), created_by FROM " + customEntityTable + " WHERE organization_id = ? ";
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 3)); // Execute at timestamp 3
        conn = DriverManager.getConnection(getUrl(), props);
        PreparedStatement statement = conn.prepareStatement(query);
        statement.setString(1, tenantId);
        ResultSet rs = statement.executeQuery();
        
        assertTrue (rs.next());
        assertEquals("00A", rs.getString(1));
        assertEquals("1", rs.getString(2));
        assertEquals(A_VALUE, rs.getString(3));
        
        assertTrue (rs.next());
        assertEquals("00A", rs.getString(1));
        assertEquals("2", rs.getString(2));
        assertEquals(A_VALUE, rs.getString(3));
        
        assertTrue (rs.next());
        assertEquals("00A", rs.getString(1));
        assertEquals("3", rs.getString(2));
        assertEquals(A_VALUE, rs.getString(3));
        
        assertTrue (rs.next());
        assertEquals("00A", rs.getString(1));
        assertEquals("4", rs.getString(2));
        assertEquals(A_VALUE, rs.getString(3));

        assertFalse(rs.next());
        conn.close();

        // Test UPSERT through coprocessor
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 4));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true);
        upsert = "UPSERT INTO " + customEntityTable + "(custom_entity_data_id, key_prefix, organization_id, last_update_by, division) " +
            "SELECT custom_entity_data_id, key_prefix, organization_id, created_by, 1.0  FROM " + customEntityTable + " WHERE organization_id = ? and created_by >= 'a'";
        
        upsertStmt = conn.prepareStatement(upsert);
        upsertStmt.setString(1, tenantId);
        assertEquals(4, upsertStmt.executeUpdate());
        conn.commit();

        query = "SELECT key_prefix, substr(custom_entity_data_id, 1, 1), created_by, last_update_by, division FROM " + customEntityTable + " WHERE organization_id = ?";
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 5)); 
        conn = DriverManager.getConnection(getUrl(), props);
        statement = conn.prepareStatement(query);
        statement.setString(1, tenantId);
        rs = statement.executeQuery();
       
        assertTrue (rs.next());
        assertEquals("00A", rs.getString(1));
        assertEquals("1", rs.getString(2));
        assertEquals(A_VALUE, rs.getString(3));
        assertEquals(A_VALUE, rs.getString(4));
        assertTrue(BigDecimal.valueOf(1.0).compareTo(rs.getBigDecimal(5)) == 0);
        
        assertTrue (rs.next());
        assertEquals("00A", rs.getString(1));
        assertEquals("2", rs.getString(2));
        assertEquals(A_VALUE, rs.getString(3));
        assertEquals(A_VALUE, rs.getString(4));
        assertTrue(BigDecimal.valueOf(1.0).compareTo(rs.getBigDecimal(5)) == 0);
        
        assertTrue (rs.next());
        assertEquals("00A", rs.getString(1));
        assertEquals("3", rs.getString(2));
        assertEquals(A_VALUE, rs.getString(3));
        assertEquals(A_VALUE, rs.getString(4));
        assertTrue(BigDecimal.valueOf(1.0).compareTo(rs.getBigDecimal(5)) == 0);
        
        assertTrue (rs.next());
        assertEquals("00A", rs.getString(1));
        assertEquals("4", rs.getString(2));
        assertEquals(A_VALUE, rs.getString(3));
        assertEquals(A_VALUE, rs.getString(4));
        assertTrue(BigDecimal.valueOf(1.0).compareTo(rs.getBigDecimal(5)) == 0);

        assertFalse(rs.next());
        conn.close();
    }

    // TODO: more tests - nullable fixed length last PK column
    @Test
    public void testUpsertSelectEmptyPKColumn() throws Exception {
        long ts = nextTimestamp();
        String tenantId = getOrganizationId();
        String aTable = initATableValues(tenantId, getDefaultSplits(tenantId), null, ts-1, getUrl(), null);
        String ptsdbTable = generateUniqueName();
        ensureTableCreated(getUrl(), ptsdbTable, PTSDB_NAME, ts-1);
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 1)); // Execute at timestamp 1
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        String upsert = "UPSERT INTO " + ptsdbTable + "(\"DATE\", val, host) " +
            "SELECT current_date(), x_integer+2, entity_id FROM " + aTable + " WHERE a_integer >= ?";
        PreparedStatement upsertStmt = conn.prepareStatement(upsert);
        upsertStmt.setInt(1, 6);
        int rowsInserted = upsertStmt.executeUpdate();
        assertEquals(4, rowsInserted);
        conn.commit();
        conn.close();
        
        String query = "SELECT inst,host,\"DATE\",val FROM " + ptsdbTable;
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 2));
        conn = DriverManager.getConnection(getUrl(), props);
        PreparedStatement statement = conn.prepareStatement(query);
        ResultSet rs = statement.executeQuery();
        
        Date now = new Date(System.currentTimeMillis());
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW6, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertEquals(null, rs.getBigDecimal(4));
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW7, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(7).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW8, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(6).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW9, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(5).compareTo(rs.getBigDecimal(4)) == 0);

        assertFalse(rs.next());
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 3));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true);
        upsert = "UPSERT INTO " + ptsdbTable + "(\"DATE\", val, inst) " +
            "SELECT \"DATE\"+1, val*10, host FROM " + ptsdbTable;
        upsertStmt = conn.prepareStatement(upsert);
        rowsInserted = upsertStmt.executeUpdate();
        assertEquals(4, rowsInserted);
        conn.commit();
        conn.close();
        
        Date then = new Date(now.getTime() + QueryConstants.MILLIS_IN_DAY);
        query = "SELECT host,inst, \"DATE\",val FROM " + ptsdbTable + " where inst is not null";
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 4)); // Execute at timestamp 2
        conn = DriverManager.getConnection(getUrl(), props);
        statement = conn.prepareStatement(query);
        
        rs = statement.executeQuery();
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW6, rs.getString(2));
        assertTrue(rs.getDate(3).after(now) && rs.getDate(3).before(then));
        assertEquals(null, rs.getBigDecimal(4));
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW7, rs.getString(2));
        assertTrue(rs.getDate(3).after(now) && rs.getDate(3).before(then));
        assertTrue(BigDecimal.valueOf(70).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW8, rs.getString(2));
        assertTrue(rs.getDate(3).after(now) && rs.getDate(3).before(then));
        assertTrue(BigDecimal.valueOf(60).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW9, rs.getString(2));
        assertTrue(rs.getDate(3).after(now) && rs.getDate(3).before(then));
        assertTrue(BigDecimal.valueOf(50).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertFalse(rs.next());
        conn.close();
        
        // Should just update all values with the same value, essentially just updating the timestamp
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 4));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true);
        upsert = "UPSERT INTO " + ptsdbTable + " SELECT * FROM " + ptsdbTable;
        upsertStmt = conn.prepareStatement(upsert);
        rowsInserted = upsertStmt.executeUpdate();
        assertEquals(8, rowsInserted);
        conn.commit();
        conn.close();
        
        query = "SELECT * FROM " + ptsdbTable ;
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 4)); // Execute at timestamp 2
        conn = DriverManager.getConnection(getUrl(), props);
        statement = conn.prepareStatement(query);
        
        rs = statement.executeQuery();
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW6, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertEquals(null, rs.getBigDecimal(4));
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW7, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(7).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW8, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(6).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(ROW9, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(5).compareTo(rs.getBigDecimal(4)) == 0);

        assertTrue (rs.next());
        assertEquals(ROW6, rs.getString(1));
        assertEquals(null, rs.getString(2));
        assertTrue(rs.getDate(3).after(now) && rs.getDate(3).before(then));
        assertEquals(null, rs.getBigDecimal(4));
        
        assertTrue (rs.next());
        assertEquals(ROW7, rs.getString(1));
        assertEquals(null, rs.getString(2));
        assertTrue(rs.getDate(3).after(now) && rs.getDate(3).before(then));
        assertTrue(BigDecimal.valueOf(70).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(ROW8, rs.getString(1));
        assertEquals(null, rs.getString(2));
        assertTrue(rs.getDate(3).after(now) && rs.getDate(3).before(then));
        assertTrue(BigDecimal.valueOf(60).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(ROW9, rs.getString(1));
        assertEquals(null, rs.getString(2));
        assertTrue(rs.getDate(3).after(now) && rs.getDate(3).before(then));
        assertTrue(BigDecimal.valueOf(50).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertFalse(rs.next());
        conn.close();
    }

    @Test
    public void testUpsertSelectForAggAutoCommit() throws Exception {
        testUpsertSelectForAgg(true);
    }
    
    @Test
    public void testUpsertSelectForAgg() throws Exception {
        testUpsertSelectForAgg(false);
    }
    
    private void testUpsertSelectForAgg(boolean autoCommit) throws Exception {
        long ts = nextTimestamp();
        String tenantId = getOrganizationId();
        String aTable = initATableValues(tenantId, getDefaultSplits(tenantId), null, ts-1, getUrl(), null);
        String ptsdbTable = generateUniqueName();
        ensureTableCreated(getUrl(), ptsdbTable, PTSDB_NAME, ts-1);
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 1)); // Execute at timestamp 1
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(autoCommit);
        String upsert = "UPSERT INTO " + ptsdbTable + "(\"DATE\", val, host) " +
            "SELECT current_date(), sum(a_integer), a_string FROM " + aTable + " GROUP BY a_string";
        PreparedStatement upsertStmt = conn.prepareStatement(upsert);
        int rowsInserted = upsertStmt.executeUpdate();
        assertEquals(3, rowsInserted);
        if (!autoCommit) {
            conn.commit();
        }
        conn.close();
        
        String query = "SELECT inst,host,\"DATE\",val FROM " + ptsdbTable;
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 2));
        conn = DriverManager.getConnection(getUrl(), props);
        PreparedStatement statement = conn.prepareStatement(query);
        ResultSet rs = statement.executeQuery();
        Date now = new Date(System.currentTimeMillis());
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(A_VALUE, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(10).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(B_VALUE, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(26).compareTo(rs.getBigDecimal(4)) == 0);
        
        assertTrue (rs.next());
        assertEquals(null, rs.getString(1));
        assertEquals(C_VALUE, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(9).compareTo(rs.getBigDecimal(4)) == 0);
        assertFalse(rs.next());
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 3));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true);
        upsert = "UPSERT INTO " + ptsdbTable + "(\"DATE\", val, host, inst) " +
            "SELECT current_date(), max(val), max(host), 'x' FROM " + ptsdbTable;
        upsertStmt = conn.prepareStatement(upsert);
        rowsInserted = upsertStmt.executeUpdate();
        assertEquals(1, rowsInserted);
        if (!autoCommit) {
            conn.commit();
        }
        conn.close();
        
        query = "SELECT inst,host,\"DATE\",val FROM " + ptsdbTable + " WHERE inst='x'";
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 4));
        conn = DriverManager.getConnection(getUrl(), props);
        statement = conn.prepareStatement(query);
        rs = statement.executeQuery();
        now = new Date(System.currentTimeMillis());
        
        assertTrue (rs.next());
        assertEquals("x", rs.getString(1));
        assertEquals(C_VALUE, rs.getString(2));
        assertTrue(rs.getDate(3).before(now) );
        assertTrue(BigDecimal.valueOf(26).compareTo(rs.getBigDecimal(4)) == 0);
        assertFalse(rs.next());
        
    }

    @Test
    public void testUpsertSelectLongToInt() throws Exception {
        byte[][] splits = new byte[][] { PInteger.INSTANCE.toBytes(1), PInteger.INSTANCE.toBytes(2),
                PInteger.INSTANCE.toBytes(3), PInteger.INSTANCE.toBytes(4)};
        long ts = nextTimestamp();
        String tableName = generateUniqueName();
        ensureTableCreated(getUrl(), tableName, "IntKeyTest", splits, ts-2, null);
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 1));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        String upsert = "UPSERT INTO " + tableName + " VALUES(1)";
        PreparedStatement upsertStmt = conn.prepareStatement(upsert);
        int rowsInserted = upsertStmt.executeUpdate();
        assertEquals(1, rowsInserted);
        conn.commit();
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 5));
        conn = DriverManager.getConnection(getUrl(), props);
        upsert = "UPSERT INTO " + tableName + "  select i+1 from " + tableName;
        upsertStmt = conn.prepareStatement(upsert);
        rowsInserted = upsertStmt.executeUpdate();
        assertEquals(1, rowsInserted);
        conn.commit();
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        String select = "SELECT i FROM " + tableName;
        ResultSet rs = conn.createStatement().executeQuery(select);
        assertTrue(rs.next());
        assertEquals(1,rs.getInt(1));
        assertTrue(rs.next());
        assertEquals(2,rs.getInt(1));
        assertFalse(rs.next());
        conn.close();
    }

    @Test
    public void testUpsertSelectRunOnServer() throws Exception {
        byte[][] splits = new byte[][] { PInteger.INSTANCE.toBytes(1), PInteger.INSTANCE.toBytes(2),
                PInteger.INSTANCE.toBytes(3), PInteger.INSTANCE.toBytes(4)};
        long ts = nextTimestamp();
        String tableName = generateUniqueName();
        createTestTable(getUrl(), "create table " + tableName + " (i integer not null primary key desc, j integer)", splits, ts-2);
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 1));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        String upsert = "UPSERT INTO " + tableName + " VALUES(1, 1)";
        PreparedStatement upsertStmt = conn.prepareStatement(upsert);
        int rowsInserted = upsertStmt.executeUpdate();
        assertEquals(1, rowsInserted);
        conn.commit();
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 3));
        conn = DriverManager.getConnection(getUrl(), props);
        String select = "SELECT i,j+1 FROM " + tableName;
        ResultSet rs = conn.createStatement().executeQuery(select);
        assertTrue(rs.next());
        assertEquals(1,rs.getInt(1));
        assertEquals(2,rs.getInt(2));
        assertFalse(rs.next());
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 5));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true); // Force to run on server side.
        upsert = "UPSERT INTO " + tableName + "(i,j) select i, j+1 from " + tableName;
        upsertStmt = conn.prepareStatement(upsert);
        rowsInserted = upsertStmt.executeUpdate();
        assertEquals(1, rowsInserted);
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        select = "SELECT j FROM " + tableName;
        rs = conn.createStatement().executeQuery(select);
        assertTrue(rs.next());
        assertEquals(2,rs.getInt(1));
        assertFalse(rs.next());
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 15));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true); // Force to run on server side.
        upsert = "UPSERT INTO " + tableName + "(i,j) select i, i from " + tableName;
        upsertStmt = conn.prepareStatement(upsert);
        rowsInserted = upsertStmt.executeUpdate();
        assertEquals(1, rowsInserted);
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 20));
        conn = DriverManager.getConnection(getUrl(), props);
        select = "SELECT j FROM " + tableName;
        rs = conn.createStatement().executeQuery(select);
        assertTrue(rs.next());
        assertEquals(1,rs.getInt(1));
        assertFalse(rs.next());
        conn.close();
    }

    @Test
    public void testUpsertSelectOnDescToAsc() throws Exception {
        byte[][] splits = new byte[][] { PInteger.INSTANCE.toBytes(1), PInteger.INSTANCE.toBytes(2),
                PInteger.INSTANCE.toBytes(3), PInteger.INSTANCE.toBytes(4)};
        long ts = nextTimestamp();
        String tableName = generateUniqueName();
        createTestTable(getUrl(), "create table " + tableName + " (i integer not null primary key desc, j integer)", splits, ts-2);
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 1));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        String upsert = "UPSERT INTO " + tableName + " VALUES(1, 1)";
        PreparedStatement upsertStmt = conn.prepareStatement(upsert);
        int rowsInserted = upsertStmt.executeUpdate();
        assertEquals(1, rowsInserted);
        conn.commit();
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 5));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true); // Force to run on server side.
        upsert = "UPSERT INTO " + tableName + " (i,j) select i+1, j+1 from " + tableName;
        upsertStmt = conn.prepareStatement(upsert);
        rowsInserted = upsertStmt.executeUpdate();
        assertEquals(1, rowsInserted);
        conn.commit();
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        String select = "SELECT i,j FROM " + tableName;
        ResultSet rs = conn.createStatement().executeQuery(select);
        assertTrue(rs.next());
        assertEquals(2,rs.getInt(1));
        assertEquals(2,rs.getInt(2));
        assertTrue(rs.next());
        assertEquals(1,rs.getInt(1));
        assertEquals(1,rs.getInt(2));
        assertFalse(rs.next());
        conn.close();
    }

    @Test
    public void testUpsertSelectRowKeyMutationOnSplitedTable() throws Exception {
        byte[][] splits = new byte[][] { PInteger.INSTANCE.toBytes(1), PInteger.INSTANCE.toBytes(2),
                PInteger.INSTANCE.toBytes(3), PInteger.INSTANCE.toBytes(4)};
        long ts = nextTimestamp();
        String tableName = generateUniqueName();
        ensureTableCreated(getUrl(), tableName, "IntKeyTest", splits,ts-2, null);
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 1));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        String upsert = "UPSERT INTO " + tableName + " VALUES(?)";
        PreparedStatement upsertStmt = conn.prepareStatement(upsert);
        upsertStmt.setInt(1, 1);
        upsertStmt.executeUpdate();
        upsertStmt.setInt(1, 3);
        upsertStmt.executeUpdate();
        conn.commit();
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 5));
        conn = DriverManager.getConnection(getUrl(), props);
        // Normally this would force a server side update. But since this changes the PK column, it would
        // for to run on the client side.
        conn.setAutoCommit(true);
        upsert = "UPSERT INTO " + tableName + " (i) SELECT i+1 from " + tableName;
        upsertStmt = conn.prepareStatement(upsert);
        int rowsInserted = upsertStmt.executeUpdate();
        assertEquals(2, rowsInserted);
        conn.commit();
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        String select = "SELECT i FROM " + tableName;
        ResultSet rs = conn.createStatement().executeQuery(select);
        assertTrue(rs.next());
        assertEquals(1,rs.getInt(1));
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertEquals(4,rs.getInt(1));
        assertFalse(rs.next());
        conn.close();
    }
    
    @Test
    public void testUpsertSelectWithLimit() throws Exception {
        long ts = nextTimestamp();
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        String tableName = generateUniqueName();
        conn.createStatement().execute("create table " + tableName + " (id varchar(10) not null primary key, val varchar(10), ts timestamp)");
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.createStatement().execute("upsert into " + tableName + " values ('aaa', 'abc', current_date())");
        conn.createStatement().execute("upsert into " + tableName + " values ('bbb', 'bcd', current_date())");
        conn.createStatement().execute("upsert into " + tableName + " values ('ccc', 'cde', current_date())");
        conn.commit();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 20));
        conn = DriverManager.getConnection(getUrl(), props);
        ResultSet rs = conn.createStatement().executeQuery("select * from " + tableName);
        
        assertTrue(rs.next());
        assertEquals("aaa",rs.getString(1));
        assertEquals("abc",rs.getString(2));
        assertNotNull(rs.getDate(3));
        
        assertTrue(rs.next());
        assertEquals("bbb",rs.getString(1));
        assertEquals("bcd",rs.getString(2));
        assertNotNull(rs.getDate(3));
        
        assertTrue(rs.next());
        assertEquals("ccc",rs.getString(1));
        assertEquals("cde",rs.getString(2));
        assertNotNull(rs.getDate(3));
        
        assertFalse(rs.next());
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 30));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.createStatement().execute("upsert into " + tableName + " (id, ts) select id, CAST(null AS timestamp) from " + tableName + " where id <= 'bbb' limit 1");
        conn.commit();
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 40));
        conn = DriverManager.getConnection(getUrl(), props);
        rs = conn.createStatement().executeQuery("select * from " + tableName);
        
        assertTrue(rs.next());
        assertEquals("aaa",rs.getString(1));
        assertEquals("abc",rs.getString(2));
        assertNull(rs.getDate(3));
        
        assertTrue(rs.next());
        assertEquals("bbb",rs.getString(1));
        assertEquals("bcd",rs.getString(2));
        assertNotNull(rs.getDate(3));
        
        assertTrue(rs.next());
        assertEquals("ccc",rs.getString(1));
        assertEquals("cde",rs.getString(2));
        assertNotNull(rs.getDate(3));
        
        assertFalse(rs.next());
        conn.close();

    }
    
    @Test
    public void testUpsertSelectWithSequence() throws Exception {
        long ts = nextTimestamp();
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        String t1 = generateUniqueName();
        String t2 = generateUniqueName();
        String seq = generateUniqueName();
        conn.createStatement().execute("create table  " + t1 + " (id bigint not null primary key, v varchar)");
        conn.createStatement().execute("create table " + t2 + " (k varchar primary key)");
        conn.createStatement().execute("create sequence " + seq);
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.createStatement().execute("upsert into " + t2 + " values ('a')");
        conn.createStatement().execute("upsert into " + t2 + " values ('b')");
        conn.createStatement().execute("upsert into " + t2 + " values ('c')");
        conn.commit();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 15));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.createStatement().execute("upsert into " + t1 + " select next value for  " + seq + " , k from " + t2);
        conn.commit();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 20));
        conn = DriverManager.getConnection(getUrl(), props);
        ResultSet rs = conn.createStatement().executeQuery("select * from " + t1);
        
        assertTrue(rs.next());
        assertEquals(1,rs.getLong(1));
        assertEquals("a",rs.getString(2));
        
        assertTrue(rs.next());
        assertEquals(2,rs.getLong(1));
        assertEquals("b",rs.getString(2));
        
        assertTrue(rs.next());
        assertEquals(3,rs.getLong(1));
        assertEquals("c",rs.getString(2));
        
        assertFalse(rs.next());
        conn.close();
    }
    
    @Test
    public void testUpsertSelectWithSequenceAndOrderByWithSalting() throws Exception {

        int numOfRecords = 200;
        long ts = nextTimestamp();
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        String t1 = generateUniqueName();
        String t2 = generateUniqueName();
        String ddl = "CREATE TABLE IF NOT EXISTS " + t1 +  "("
                + "ORGANIZATION_ID CHAR(15) NOT NULL, QUERY_ID CHAR(15) NOT NULL, CURSOR_ORDER BIGINT NOT NULL, K1 INTEGER, V1 INTEGER "
                + "CONSTRAINT MAIN_PK PRIMARY KEY (ORGANIZATION_ID, QUERY_ID, CURSOR_ORDER) " + ") SALT_BUCKETS = 4";
        conn.createStatement().execute(ddl);
        conn.createStatement().execute(
                "CREATE TABLE " + t2
                        + "(ORGANIZATION_ID CHAR(15) NOT NULL, k1 integer NOT NULL, v1 integer NOT NULL "
                        + "CONSTRAINT PK PRIMARY KEY (ORGANIZATION_ID, k1, v1) ) VERSIONS=1, SALT_BUCKETS = 4");
        conn.createStatement().execute("create sequence s cache " + Integer.MAX_VALUE);
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        for (int i = 0; i < numOfRecords; i++) {
            conn.createStatement().execute(
                    "UPSERT INTO " + t2 + " values ('00Dxx0000001gEH'," + i + "," + (i + 2) + ")");
        }
        conn.commit();
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 15));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true);
        conn.createStatement().execute(
                    "UPSERT INTO " + t1 + " SELECT '00Dxx0000001gEH', 'MyQueryId', NEXT VALUE FOR S, k1, v1  FROM " + t2 + " ORDER BY K1, V1");

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 20));
        conn = DriverManager.getConnection(getUrl(), props);
        ResultSet rs = conn.createStatement().executeQuery("select count(*) from " + t1);

        assertTrue(rs.next());
        assertEquals(numOfRecords, rs.getLong(1));
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 25));
        ResultSet rs2 = conn.createStatement().executeQuery(
                "select cursor_order, k1, v1 from " + t1 + " order by cursor_order");
        long seq = 1;
        while (rs2.next()) {
            assertEquals(seq, rs2.getLong("cursor_order"));
            // This value should be the sequence - 1 as we said order by k1 in the UPSERT...SELECT, but is not because
            // of sequence processing.
            assertEquals(seq - 1, rs2.getLong("k1"));
            seq++;
        }
        conn.close();

    }
    
    @Test
    public void testUpsertSelectWithRowtimeStampColumn() throws Exception {
        long ts = nextTimestamp();
        String t1 = generateUniqueName();
        String t2 = generateUniqueName();
        String t3 = generateUniqueName();
        try (Connection conn = getConnection(ts)) {
            conn.createStatement().execute("CREATE TABLE " + t1 + " (PK1 VARCHAR NOT NULL, PK2 DATE NOT NULL, KV1 VARCHAR CONSTRAINT PK PRIMARY KEY(PK1, PK2 DESC ROW_TIMESTAMP " + ")) ");
            conn.createStatement().execute("CREATE TABLE " + t2 + " (PK1 VARCHAR NOT NULL, PK2 DATE NOT NULL, KV1 VARCHAR CONSTRAINT PK PRIMARY KEY(PK1, PK2 ROW_TIMESTAMP)) ");
            conn.createStatement().execute("CREATE TABLE " + t3 + " (PK1 VARCHAR NOT NULL, PK2 DATE NOT NULL, KV1 VARCHAR CONSTRAINT PK PRIMARY KEY(PK1, PK2 DESC ROW_TIMESTAMP " + ")) ");
        }
        
        // Upsert data with scn set on the connection. However, the timestamp of the put will be the value of the row_timestamp column.
        ts = nextTimestamp();
        long rowTimestamp = 1000000;
        Date rowTimestampDate = new Date(rowTimestamp);
        try (Connection conn = getConnection(ts)) {
            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + t1 + " (PK1, PK2, KV1) VALUES(?, ?, ?)");
            stmt.setString(1, "PK1");
            stmt.setDate(2, rowTimestampDate);
            stmt.setString(3, "KV1");
            stmt.executeUpdate();
            conn.commit();
        }
        
        // Upsert select data into table T2. The connection needs to be at a timestamp beyond the row timestamp. Otherwise 
        // it won't see the data from table T1.
        try (Connection conn = getConnection(rowTimestamp + 5)) {
            conn.createStatement().executeUpdate("UPSERT INTO " + t2 + " SELECT * FROM " + t1);
            conn.commit();
            // Verify the data upserted in T2. Note that we can use the same connection here because the data was
            // inserted with a timestamp of rowTimestamp and the connection is at rowTimestamp + 5.
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + t2 + " WHERE PK1 = ? AND PK2 = ?");
            stmt.setString(1, "PK1");
            stmt.setDate(2, rowTimestampDate);
            ResultSet rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals("PK1", rs.getString("PK1"));
            assertEquals(rowTimestampDate, rs.getDate("PK2"));
            assertEquals("KV1", rs.getString("KV1"));
        }
        
        // Verify that you can't see the data in T2 if the connection is at next timestamp (which is lower than the row timestamp).
        try (Connection conn = getConnection(nextTimestamp())) {
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + t2 + " WHERE PK1 = ? AND PK2 = ?");
            stmt.setString(1, "PK1");
            stmt.setDate(2, rowTimestampDate);
            ResultSet rs = stmt.executeQuery();
            assertFalse(rs.next());
        }
        
        // Upsert select data into table T3. The connection needs to be at a timestamp beyond the row timestamp. Otherwise 
        // it won't see the data from table T1.
        try (Connection conn = getConnection(rowTimestamp + 5)) {
            conn.createStatement().executeUpdate("UPSERT INTO " + t3 + " SELECT * FROM " + t1);
            conn.commit();
            // Verify the data upserted in T3. Note that we can use the same connection here because the data was
            // inserted with a timestamp of rowTimestamp and the connection is at rowTimestamp + 5.
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + t3 + " WHERE PK1 = ? AND PK2 = ?");
            stmt.setString(1, "PK1");
            stmt.setDate(2, rowTimestampDate);
            ResultSet rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals("PK1", rs.getString("PK1"));
            assertEquals(rowTimestampDate, rs.getDate("PK2"));
            assertEquals("KV1", rs.getString("KV1"));
        }
        
        // Verify that you can't see the data in T2 if the connection is at next timestamp (which is lower than the row timestamp).
        try (Connection conn = getConnection(nextTimestamp())) {
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + t3 + " WHERE PK1 = ? AND PK2 = ?");
            stmt.setString(1, "PK1");
            stmt.setDate(2, rowTimestampDate);
            ResultSet rs = stmt.executeQuery();
            assertFalse(rs.next());
        }
    }
    
    @Test
    public void testUpsertSelectSameTableWithRowTimestampColumn() throws Exception {
        String tableName = generateUniqueName();
        long ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            conn.createStatement().execute("CREATE TABLE " + tableName + " (PK1 INTEGER NOT NULL, PK2 DATE NOT NULL, KV1 VARCHAR CONSTRAINT PK PRIMARY KEY(PK1, PK2 ROW_TIMESTAMP)) ");
        }

        // Upsert data with scn set on the connection. The timestamp of the put will be the value of the row_timestamp column.
        ts = nextTimestamp();
        long rowTimestamp = ts + 100000;
        Date rowTimestampDate = new Date(rowTimestamp);
        try (Connection conn = getConnection(ts)) {
            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO  " + tableName + " (PK1, PK2, KV1) VALUES(?, ?, ?)");
            stmt.setInt(1, 1);
            stmt.setDate(2, rowTimestampDate);
            stmt.setString(3, "KV1");
            stmt.executeUpdate();
            conn.commit();
        }
        String seq = generateUniqueName();
        try (Connection conn = getConnection(nextTimestamp())) {
            conn.createStatement().execute("CREATE SEQUENCE " + seq);
        }
        // Upsert select data into table. The connection needs to be at a timestamp beyond the row timestamp. Otherwise 
        // it won't see the data from table.
        try (Connection conn = getConnection(rowTimestamp + 5)) {
            conn.createStatement().executeUpdate("UPSERT INTO  " + tableName + "  SELECT NEXT VALUE FOR " + seq + ", PK2 FROM  " + tableName);
            conn.commit();
        }
        
        // Upsert select using sequences.
        try (Connection conn = getConnection(rowTimestamp + 5)) {
            conn.setAutoCommit(true);
            for (int i = 0; i < 10; i++) {
                int count = conn.createStatement().executeUpdate("UPSERT INTO  " + tableName + "  SELECT NEXT VALUE FOR " + seq + ", PK2 FROM  " + tableName);
                assertEquals((int)Math.pow(2, i), count);
            }
        }
    }
    
    @Test
    public void testAutomaticallySettingRowtimestamp() throws Exception {
        String table1 = generateUniqueName();
        String table2 = generateUniqueName();
        String table3 = generateUniqueName();
        long ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            conn.createStatement().execute("CREATE TABLE " + table1 + " (T1PK1 VARCHAR NOT NULL, T1PK2 DATE NOT NULL, T1KV1 VARCHAR, T1KV2 VARCHAR CONSTRAINT PK PRIMARY KEY(T1PK1, T1PK2 DESC ROW_TIMESTAMP)) ");
            conn.createStatement().execute("CREATE TABLE " + table2 + " (T2PK1 VARCHAR NOT NULL, T2PK2 DATE NOT NULL, T2KV1 VARCHAR, T2KV2 VARCHAR CONSTRAINT PK PRIMARY KEY(T2PK1, T2PK2 ROW_TIMESTAMP)) ");
            conn.createStatement().execute("CREATE TABLE " + table3 + " (T3PK1 VARCHAR NOT NULL, T3PK2 DATE NOT NULL, T3KV1 VARCHAR, T3KV2 VARCHAR CONSTRAINT PK PRIMARY KEY(T3PK1, T3PK2 DESC ROW_TIMESTAMP)) ");
        }
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            // Upsert values where row_timestamp column PK2 is not set and the column names are specified
            // This should upsert data with the value for PK2 as new Date(ts);
            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO  " + table1 + " (T1PK1, T1KV1, T1KV2) VALUES (?, ?, ?)");
            stmt.setString(1, "PK1");
            stmt.setString(2, "KV1");
            stmt.setString(3, "KV2");
            stmt.executeUpdate();
            conn.commit();
        }
        Date upsertedDate = new Date(ts);
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            // Now query for data that was upserted above. If the row key was generated correctly then we should be able to see
            // the data in this query.
            PreparedStatement stmt = conn.prepareStatement("SELECT T1KV1, T1KV2 FROM " + table1 + " WHERE T1PK1 = ? AND T1PK2 = ?");
            stmt.setString(1, "PK1");
            stmt.setDate(2, upsertedDate);
            ResultSet rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals("KV1", rs.getString(1));
            assertEquals("KV2", rs.getString(2));
            assertFalse(rs.next());
        }
        
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            // Upsert select into table2 by not selecting the row timestamp column. In this case, the rowtimestamp column would end up being set to the scn of the connection
            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO  " + table2 + " (T2PK1, T2KV1, T2KV2) SELECT T1PK1, T1KV1, T1KV2 FROM " + table1);
            stmt.executeUpdate();
            conn.commit();
        }
        
        upsertedDate = new Date(ts);
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            // Now query for data that was upserted above. If the row key was generated correctly then we should be able to see
            // the data in this query.
            PreparedStatement stmt = conn.prepareStatement("SELECT T2KV1, T2KV2 FROM " + table2 + " WHERE T2PK1 = ? AND T2PK2 = ?");
            stmt.setString(1, "PK1");
            stmt.setDate(2, upsertedDate);
            ResultSet rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals("KV1", rs.getString(1));
            assertEquals("KV2", rs.getString(2));
            assertFalse(rs.next());
        }
        
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            // Upsert select into table2 by not selecting the row timestamp column. In this case, the rowtimestamp column would end up being set to the scn of the connection
            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO  " + table3 + " (T3PK1, T3KV1, T3KV2) SELECT T2PK1, T2KV1, T2KV2 FROM " + table2);
            stmt.executeUpdate();
            conn.commit();
        }
        
        upsertedDate = new Date(ts);
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            // Now query for data that was upserted above. If the row key was generated correctly then we should be able to see
            // the data in this query.
            PreparedStatement stmt = conn.prepareStatement("SELECT T3KV1, T3KV2 FROM " + table3 + " WHERE T3PK1 = ? AND T3PK2 = ?");
            stmt.setString(1, "PK1");
            stmt.setDate(2, upsertedDate);
            ResultSet rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals("KV1", rs.getString(1));
            assertEquals("KV2", rs.getString(2));
            assertFalse(rs.next());
        }
    }
    
    @Test
    public void testUpsertSelectAutoCommitWithRowTimestampColumn() throws Exception {
        String tableName1 = generateUniqueName();
        String tableName2 = generateUniqueName();
        long ts = 10;
        try (Connection conn = getConnection(ts)) {
            conn.createStatement().execute("CREATE TABLE " + tableName1 + " (PK1 INTEGER NOT NULL, PK2 DATE NOT NULL, PK3 INTEGER NOT NULL, KV1 VARCHAR CONSTRAINT PK PRIMARY KEY(PK1, PK2 ROW_TIMESTAMP, PK3)) ");
            conn.createStatement().execute("CREATE TABLE " + tableName2 + " (PK1 INTEGER NOT NULL, PK2 DATE NOT NULL, PK3 INTEGER NOT NULL, KV1 VARCHAR CONSTRAINT PK PRIMARY KEY(PK1, PK2 DESC ROW_TIMESTAMP, PK3)) ");
        }

        String[] tableNames = {tableName1, tableName2};
        for (String tableName : tableNames) {
            // Upsert data with scn set on the connection. The timestamp of the put will be the value of the row_timestamp column.
            long rowTimestamp1 = 100;
            Date rowTimestampDate = new Date(rowTimestamp1);
            try (Connection conn = getConnection(ts+1)) {
                PreparedStatement stmt = conn.prepareStatement("UPSERT INTO  " + tableName + " (PK1, PK2, PK3, KV1) VALUES(?, ?, ?, ?)");
                stmt.setInt(1, 1);
                stmt.setDate(2, rowTimestampDate);
                stmt.setInt(3, 3);
                stmt.setString(4, "KV1");
                stmt.executeUpdate();
                conn.commit();
            }

            long rowTimestamp2 = 2 * rowTimestamp1;
            try (Connection conn = getConnection(rowTimestamp2)) {
                conn.setAutoCommit(true);
                // Upsert select in the same table with the row_timestamp column PK2 not specified. This will end up
                // creating a new row whose timestamp is the SCN of the connection. The same SCN will be used
                // for the row key too.
                conn.createStatement().executeUpdate("UPSERT INTO  " + tableName + " (PK1, PK3, KV1) SELECT PK1, PK3, KV1 FROM  " + tableName);
            }
            try (Connection conn = getConnection(3 * rowTimestamp1)) {
                // Verify the row that was upserted above
                PreparedStatement stmt = conn.prepareStatement("SELECT * FROM  " + tableName + " WHERE PK1 = ? AND PK2 = ? AND PK3 = ?");
                stmt.setInt(1, 1);
                stmt.setDate(2, new Date(rowTimestamp2));
                stmt.setInt(3, 3);
                ResultSet rs = stmt.executeQuery();
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("PK1"));
                assertEquals(3, rs.getInt("PK3"));
                assertEquals("KV1", rs.getString("KV1"));
                assertEquals(new Date(rowTimestamp2), rs.getDate("PK2"));
                assertFalse(rs.next());
                // Number of rows in the table should be 2.
                rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + tableName);
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));

            }
            ts = 5 * rowTimestamp1;
            try (Connection conn = getConnection(ts)) {
                conn.setAutoCommit(true);
                // Upsert select in the same table with the row_timestamp column PK2 specified. This will not end up creating a new row
                // because the destination pk columns, including the row timestamp column PK2, are the same as the source column.
                conn.createStatement().executeUpdate("UPSERT INTO  " + tableName + " (PK1, PK2, PK3, KV1) SELECT PK1, PK2, PK3, KV1 FROM  " + tableName);
            }
            ts = 6 * rowTimestamp1;
            try (Connection conn = getConnection(ts)) {
                // Verify that two rows were created. One with rowtimestamp1 and the other with rowtimestamp2
                ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + tableName);
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertFalse(rs.next());
            }
            
        }
    }
    
    @Test
    public void testRowTimestampColWithViewsIndexesAndSaltedTables() throws Exception {
        String baseTable = generateUniqueName();
        String tenantView = generateUniqueName();
        String globalView = generateUniqueName();
        String baseTableIdx = generateUniqueName();
        String tenantViewIdx = generateUniqueName();

        long ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            conn.createStatement().execute("CREATE TABLE " + baseTable + " (TENANT_ID CHAR(15) NOT NULL, PK2 DATE NOT NULL, PK3 INTEGER NOT NULL, KV1 VARCHAR, KV2 VARCHAR, KV3 VARCHAR CONSTRAINT PK PRIMARY KEY(TENANT_ID, PK2 ROW_TIMESTAMP, PK3)) MULTI_TENANT = true, SALT_BUCKETS = 8");
        }
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            conn.createStatement().execute("CREATE INDEX " + baseTableIdx + " ON " + baseTable + " (PK2, KV3) INCLUDE (KV1)");
        }
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            conn.createStatement().execute("CREATE VIEW " + globalView + " AS SELECT * FROM " + baseTable + " WHERE KV1 = 'KV1'");
        }
        String tenantId = "tenant1";
        ts = nextTimestamp();
        try (Connection conn = getTenantConnection(tenantId, ts)) {
            conn.createStatement().execute("CREATE VIEW " + tenantView + " AS SELECT * FROM " + baseTable);
        }
        ts = nextTimestamp();
        try (Connection conn = getTenantConnection(tenantId, ts)) {
            conn.createStatement().execute("CREATE INDEX " + tenantViewIdx + " ON " + tenantView + " (PK2, KV2) INCLUDE (KV1)");
        }

        // upsert data into base table without specifying the row timestamp column PK2
        long upsertedTs = nextTimestamp();
        try (Connection conn = getConnection(upsertedTs)) {
            // Upsert select in the same table with the row_timestamp column PK2 not specified. This will end up
            // creating a new row whose timestamp is the SCN of the connection. The same SCN will be used
            // for the row key too.
            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO  " + baseTable + " (TENANT_ID, PK3, KV1, KV2, KV3) VALUES (?, ?, ?, ?, ?)");
            stmt.setString(1, tenantId);
            stmt.setInt(2, 3);
            stmt.setString(3, "KV1");
            stmt.setString(4, "KV2");
            stmt.setString(5, "KV3");
            stmt.executeUpdate();
            conn.commit();
        }

        // Verify that we can see data when querying through base table, global view and index on the base table
        try (Connection conn = getConnection(nextTimestamp())) {
            // Query the base table
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM  " + baseTable + " WHERE TENANT_ID = ? AND PK2 = ? AND PK3 = ?");
            stmt.setString(1, tenantId);
            stmt.setDate(2, new Date(upsertedTs));
            stmt.setInt(3, 3);
            ResultSet rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(tenantId, rs.getString("TENANT_ID"));
            assertEquals("KV1", rs.getString("KV1"));
            assertEquals("KV2", rs.getString("KV2"));
            assertEquals("KV3", rs.getString("KV3"));
            assertEquals(new Date(upsertedTs), rs.getDate("PK2"));
            assertFalse(rs.next());

            // Query the globalView
            stmt = conn.prepareStatement("SELECT * FROM  " + globalView + " WHERE TENANT_ID = ? AND PK2 = ? AND PK3 = ?");
            stmt.setString(1, tenantId);
            stmt.setDate(2, new Date(upsertedTs));
            stmt.setInt(3, 3);
            rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(tenantId, rs.getString("TENANT_ID"));
            assertEquals("KV1", rs.getString("KV1"));
            assertEquals("KV2", rs.getString("KV2"));
            assertEquals("KV3", rs.getString("KV3"));
            assertEquals(new Date(upsertedTs), rs.getDate("PK2"));
            assertFalse(rs.next());

            // Query using the index on base table
            stmt = conn.prepareStatement("SELECT KV1 FROM  " + baseTable + " WHERE PK2 = ? AND KV3 = ?");
            stmt.setDate(1, new Date(upsertedTs));
            stmt.setString(2, "KV3");
            rs = stmt.executeQuery();
            QueryPlan plan = stmt.unwrap(PhoenixStatement.class).getQueryPlan();
            assertTrue(plan.getTableRef().getTable().getName().getString().equals(baseTableIdx));
            assertTrue(rs.next());
            assertEquals("KV1", rs.getString("KV1"));
            assertFalse(rs.next());
        }

        // Verify that data can be queried using tenant view and tenant view index
        try (Connection tenantConn = getTenantConnection(tenantId, nextTimestamp())) {
            // Query the tenant view
            PreparedStatement stmt = tenantConn.prepareStatement("SELECT * FROM  " + tenantView + " WHERE PK2 = ? AND PK3 = ?");
            stmt.setDate(1, new Date(upsertedTs));
            stmt.setInt(2, 3);
            ResultSet rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals("KV1", rs.getString("KV1"));
            assertEquals("KV2", rs.getString("KV2"));
            assertEquals("KV3", rs.getString("KV3"));
            assertEquals(new Date(upsertedTs), rs.getDate("PK2"));
            assertFalse(rs.next());

            // Query using the index on the tenantView
            //TODO: uncomment the code after PHOENIX-2277 is fixed
//            stmt = tenantConn.prepareStatement("SELECT KV1 FROM  " + tenantView + " WHERE PK2 = ? AND KV2 = ?");
//            stmt.setDate(1, new Date(upsertedTs));
//            stmt.setString(2, "KV2");
//            rs = stmt.executeQuery();
//            QueryPlan plan = stmt.unwrap(PhoenixStatement.class).getQueryPlan();
//            assertTrue(plan.getTableRef().getTable().getName().getString().equals(tenantViewIdx));
//            assertTrue(rs.next());
//            assertEquals("KV1", rs.getString("KV1"));
//            assertFalse(rs.next());
        }

        upsertedTs = nextTimestamp();
        try (Connection tenantConn = getTenantConnection(tenantId, upsertedTs)) {
            // Upsert into tenant view where the row_timestamp column PK2 is not specified
            PreparedStatement stmt = tenantConn.prepareStatement("UPSERT INTO  " + tenantView + " (PK3, KV1, KV2, KV3) VALUES (?, ?, ?, ?)");
            stmt.setInt(1, 33);
            stmt.setString(2, "KV13");
            stmt.setString(3, "KV23");
            stmt.setString(4, "KV33");
            stmt.executeUpdate();
            tenantConn.commit();
            // Upsert into tenant view where the row_timestamp column PK2 is specified
            stmt = tenantConn.prepareStatement("UPSERT INTO  " + tenantView + " (PK2, PK3, KV1, KV2, KV3) VALUES (?, ?, ?, ?, ?)");
            stmt.setDate(1, new Date(upsertedTs));
            stmt.setInt(2, 44);
            stmt.setString(3, "KV14");
            stmt.setString(4, "KV24");
            stmt.setString(5, "KV34");
            stmt.executeUpdate();
            tenantConn.commit();
        }

        // Verify that the data upserted using the tenant view can now be queried using base table and the base table index
        try (Connection conn = getConnection(upsertedTs + 10000)) {
            // Query the base table
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM  " + baseTable + " WHERE TENANT_ID = ? AND PK2 = ? AND PK3 = ? ");
            stmt.setString(1, tenantId);
            stmt.setDate(2, new Date(upsertedTs));
            stmt.setInt(3, 33);
            ResultSet rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(tenantId, rs.getString("TENANT_ID"));
            assertEquals("KV13", rs.getString("KV1"));
            assertEquals("KV23", rs.getString("KV2"));
            assertEquals("KV33", rs.getString("KV3"));
            assertFalse(rs.next());
            
            stmt = conn.prepareStatement("SELECT * FROM  " + baseTable + " WHERE TENANT_ID = ? AND PK2 = ? AND PK3 = ? ");
            stmt.setString(1, tenantId);
            stmt.setDate(2, new Date(upsertedTs));
            stmt.setInt(3, 44);
            rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(tenantId, rs.getString("TENANT_ID"));
            assertEquals("KV14", rs.getString("KV1"));
            assertEquals("KV24", rs.getString("KV2"));
            assertEquals("KV34", rs.getString("KV3"));
            assertFalse(rs.next());

            // Query using the index on base table
            stmt = conn.prepareStatement("SELECT KV1 FROM  " + baseTable + " WHERE (PK2, KV3) IN ((?, ?), (?, ?)) ORDER BY KV1");
            stmt.setDate(1, new Date(upsertedTs));
            stmt.setString(2, "KV33");
            stmt.setDate(3, new Date(upsertedTs));
            stmt.setString(4, "KV34");
            rs = stmt.executeQuery();
            QueryPlan plan = stmt.unwrap(PhoenixStatement.class).getQueryPlan();
            assertTrue(plan.getTableRef().getTable().getName().getString().equals(baseTableIdx));
            assertTrue(rs.next());
            assertEquals("KV13", rs.getString("KV1"));
            assertTrue(rs.next());
            assertEquals("KV14", rs.getString("KV1"));
            assertFalse(rs.next());
        }
        
        // Verify that the data upserted using the tenant view can now be queried using tenant view
        try (Connection tenantConn = getTenantConnection(tenantId, upsertedTs + 10000)) {
            // Query the base table
            PreparedStatement stmt = tenantConn.prepareStatement("SELECT * FROM  " + tenantView + " WHERE (PK2, PK3) IN ((?, ?), (?, ?)) ORDER BY KV1");
            stmt.setDate(1, new Date(upsertedTs));
            stmt.setInt(2, 33);
            stmt.setDate(3, new Date(upsertedTs));
            stmt.setInt(4, 44);
            ResultSet rs = stmt.executeQuery();
            assertTrue(rs.next());
            assertEquals("KV13", rs.getString("KV1"));
            assertTrue(rs.next());
            assertEquals("KV14", rs.getString("KV1"));
            assertFalse(rs.next());
            
            //TODO: uncomment the code after PHOENIX-2277 is fixed
//            // Query using the index on the tenantView
//            stmt = tenantConn.prepareStatement("SELECT KV1 FROM  " + tenantView + " WHERE (PK2, KV2) IN (?, ?, ?, ?) ORDER BY KV1");
//            stmt.setDate(1, new Date(upsertedTs));
//            stmt.setString(2, "KV23");
//            stmt.setDate(3, new Date(upsertedTs));
//            stmt.setString(4, "KV24");
//            rs = stmt.executeQuery();
//            QueryPlan plan = stmt.unwrap(PhoenixStatement.class).getQueryPlan();
//            assertTrue(plan.getTableRef().getTable().getName().getString().equals(tenantViewIdx));
//            assertTrue(rs.next());
//            assertEquals("KV13", rs.getString("KV1"));
//            assertTrue(rs.next());
//            assertEquals("KV14", rs.getString("KV1"));
//            assertFalse(rs.next());
        }
    }
        
    @Test
    public void testDisallowNegativeValuesForRowTsColumn() throws Exception {
        String tableName = generateUniqueName();
        String tableName2 = generateUniqueName();
        long ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            conn.createStatement().execute("CREATE TABLE " + tableName + " (PK1 BIGINT NOT NULL PRIMARY KEY ROW_TIMESTAMP, KV1 VARCHAR)");
            conn.createStatement().execute("CREATE TABLE " + tableName2 + " (PK1 BIGINT NOT NULL PRIMARY KEY ROW_TIMESTAMP, KV1 VARCHAR)");
        }
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            long upsertedTs = 100;
            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + tableName +  " VALUES (?, ?)");
            stmt.setLong(1, upsertedTs);
            stmt.setString(2, "KV1");
            stmt.executeUpdate();
            conn.commit();
        }
        ts = nextTimestamp();
        try (Connection conn = getConnection(ts)) {
            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + tableName2 +  " SELECT (PK1 - 500), KV1 FROM " + tableName);
            stmt.executeUpdate();
            fail();
        } catch (SQLException e) {
            assertEquals(SQLExceptionCode.ILLEGAL_DATA.getErrorCode(), e.getErrorCode());
        }
    }
    
    @Test
    public void testUpsertSelectWithFixedWidthNullByteSizeArray() throws Exception {
        long ts = nextTimestamp();
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        String t1 = generateUniqueName();
        conn.createStatement().execute(
                "create table " + t1 + " (id bigint not null primary key, ca char(3)[])");
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.createStatement().execute("upsert into " + t1 + " values (1, ARRAY['aaa', 'bbb'])");
        conn.commit();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 15));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.createStatement().execute(
                "upsert into " + t1 + " (id, ca) select id, ARRAY['ccc', 'ddd'] from " + t1 + " WHERE id = 1");
        conn.commit();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 20));
        conn = DriverManager.getConnection(getUrl(), props);
        ResultSet rs = conn.createStatement().executeQuery("select * from " + t1);

        assertTrue(rs.next());
        assertEquals(1, rs.getLong(1));
        assertEquals("['ccc', 'ddd']", rs.getArray(2).toString());

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 25));
        conn = DriverManager.getConnection(getUrl(), props);
        String t2 = generateUniqueName();
        conn.createStatement().execute(
                "create table " + t2 + " (id bigint not null primary key, ba binary(4)[])");
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 30));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.createStatement().execute("upsert into " + t2 + " values (2, ARRAY[1, 27])");
        conn.commit();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 35));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.createStatement().execute(
                "upsert into " + t2 + " (id, ba) select id, ARRAY[54, 1024] from " + t2 + " WHERE id = 2");
        conn.commit();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 40));
        conn = DriverManager.getConnection(getUrl(), props);
        rs = conn.createStatement().executeQuery("select * from " + t2);

        assertTrue(rs.next());
        assertEquals(2, rs.getLong(1));
        assertEquals("[[128,0,0,54], [128,0,4,0]]", rs.getArray(2).toString());
    }

    @Test
    public void testUpsertSelectWithMultiByteCharsNoAutoCommit() throws Exception {
        testUpsertSelectWithMultiByteChars(false);
    }

    @Test
    public void testUpsertSelectWithMultiByteCharsAutoCommit() throws Exception {
        testUpsertSelectWithMultiByteChars(true);
    }

    private void testUpsertSelectWithMultiByteChars(boolean autoCommit) throws Exception {
        long ts = nextTimestamp();
        Properties props = new Properties();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(autoCommit);
        String t1 = generateUniqueName();
        conn.createStatement().execute(
                "create table " + t1 + " (id bigint not null primary key, v varchar(20))");
        conn.close();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(autoCommit);
        conn.createStatement().execute("upsert into " + t1 + " values (1, 'foo')");
        conn.commit();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 15));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(autoCommit);
        conn.createStatement().execute(
                "upsert into " + t1 + " (id, v) select id, '澴粖蟤य褻酃岤豦팑薰鄩脼ժ끦碉碉碉碉碉碉' from " + t1 + " WHERE id = 1");
        conn.commit();

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 20));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(autoCommit);
        ResultSet rs = conn.createStatement().executeQuery("select * from  " + t1);

        assertTrue(rs.next());
        assertEquals(1, rs.getLong(1));
        assertEquals("澴粖蟤य褻酃岤豦팑薰鄩脼ժ끦碉碉碉碉碉碉", rs.getString(2));

        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 25));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(autoCommit);
        try {
            conn.createStatement().execute(
                    "upsert into  " + t1 + " (id, v) select id, '澴粖蟤य褻酃岤豦팑薰鄩脼ժ끦碉碉碉碉碉碉碉' from " + t1 + " WHERE id = 1");
            conn.commit();
            fail();
        } catch (SQLException e) {
            assertEquals(SQLExceptionCode.DATA_EXCEEDS_MAX_CAPACITY.getErrorCode(), e.getErrorCode());
        }
    }

    @Test
    public void testParallelUpsertSelect() throws Exception {
        long ts = nextTimestamp();
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts));
        props.setProperty(QueryServices.MUTATE_BATCH_SIZE_BYTES_ATTRIB, Integer.toString(512));
        props.setProperty(QueryServices.SCAN_CACHE_SIZE_ATTRIB, Integer.toString(3));
        props.setProperty(QueryServices.SCAN_RESULT_CHUNK_SIZE, Integer.toString(3));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        String t1 = generateUniqueName();
        String t2 = generateUniqueName();
        String seq = generateUniqueName();
        conn.createStatement().execute("CREATE SEQUENCE " + seq);
        conn.createStatement().execute("CREATE TABLE  " + t1 + "  (pk INTEGER PRIMARY KEY, val INTEGER) SALT_BUCKETS=4");
        conn.createStatement().execute("CREATE TABLE  " + t2 + "  (pk INTEGER PRIMARY KEY, val INTEGER)");
        conn.close();
        
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 10));
        conn = DriverManager.getConnection(getUrl(), props);
        for (int i = 0; i < 100; i++) {
            conn.createStatement().execute("UPSERT INTO  " + t1 + "  VALUES (NEXT VALUE FOR " + seq + ", " + (i%10) + ")");
        }
        conn.commit();
        conn.close();
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts + 20));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true);
        int upsertCount = conn.createStatement().executeUpdate("UPSERT INTO " + t2 + " SELECT pk, val FROM  " + t1);
        assertEquals(100,upsertCount);
        conn.close();
    }

    private static Connection getConnection(long ts) throws SQLException {
        Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts));
        return DriverManager.getConnection(getUrl(), props);
    }
    
    private static Connection getTenantConnection(String tenantId, long ts) throws Exception {
        Properties props = PropertiesUtil.deepCopy(TestUtil.TEST_PROPERTIES);
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(ts));
        props.setProperty(TENANT_ID_ATTRIB, tenantId);
        return DriverManager.getConnection(getUrl(), props);
    }
    
}
