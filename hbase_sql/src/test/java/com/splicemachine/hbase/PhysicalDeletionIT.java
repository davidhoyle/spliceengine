/*
 * Copyright (c) 2012 - 2017 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.hbase;

import com.splicemachine.access.HConfiguration;
import com.splicemachine.derby.test.framework.SpliceSchemaWatcher;
import com.splicemachine.derby.test.framework.SpliceUnitTest;
import com.splicemachine.derby.test.framework.SpliceWatcher;
import com.splicemachine.derby.test.framework.TestConnection;
import com.splicemachine.derby.utils.SpliceAdmin;
import com.splicemachine.test_tools.TableCreator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.junit.*;

import java.sql.ResultSet;

import static com.splicemachine.test_tools.Rows.row;
import static com.splicemachine.test_tools.Rows.rows;

/**
 * Created by jyuan on 5/24/17.
 */
public class PhysicalDeletionIT extends SpliceUnitTest {

    private static final String SCHEMA = PhysicalDeletionIT.class.getSimpleName().toUpperCase();
    private static final SpliceWatcher classWatcher = new SpliceWatcher(SCHEMA);

    @ClassRule
    public static SpliceSchemaWatcher spliceSchemaWatcher = new SpliceSchemaWatcher(SCHEMA);

    @Rule
    public SpliceWatcher methodWatcher = new SpliceWatcher(SCHEMA);

    @BeforeClass
    public static void createTables() throws Exception {
        TestConnection conn = classWatcher.getOrCreateConnection();
        new TableCreator(conn)
                .withCreate("create table A (i int, j int, primary key(i))")
                .withInsert("insert into A values(?, ?)")
                .withRows(rows(
                        row(1, 1),
                        row(2, 2),
                        row(3, 3),
                        row(4, 4)))
                .create();
    }

    @Test
    public void testPhysicalDelete() throws Exception {

        TestConnection conn = classWatcher.getOrCreateConnection();

        String sql = "select purge_deleted_rows from sys.systables t, sys.sysschemas s where tablename='A' and " +
                "schemaname='PHYSICALDELETIONIT' and t.schemaid=s.schemaid";
        ResultSet rs = methodWatcher.executeQuery(sql);
        assert rs.next();
        boolean purgeDeletedRows = rs.getBoolean(1);
        Assert.assertTrue(!purgeDeletedRows);

        methodWatcher.execute("call syscs_util.SET_PURGE_DELETED_ROWS('PHYSICALDELETIONIT','A',true)");

        rs = methodWatcher.executeQuery(sql);
        assert rs.next();
        purgeDeletedRows = rs.getBoolean(1);
        Assert.assertTrue(purgeDeletedRows);

        methodWatcher.executeUpdate("delete from A");
        methodWatcher.executeUpdate("insert into a values(1,1), (2,2)");

        Configuration config = HConfiguration.unwrapDelegate();
        HBaseAdmin admin = new HBaseAdmin(config);
        long[] conglomId = SpliceAdmin.getConglomNumbers(conn, SCHEMA, "A");
        String hTableName = "splice:" + Long.toString(conglomId[0]);
        admin.flush(TableName.valueOf(hTableName));
        Thread.sleep(10000);
        methodWatcher.execute("call syscs_util.SYSCS_PERFORM_MAJOR_COMPACTION_ON_TABLE('PHYSICALDELETIONIT','A')");

        HTable table = new HTable(config, hTableName);

        Scan s = new Scan();
        // Let scanner know which columns we are interested in
        ResultScanner scanner = table.getScanner(s);
        int count = 0;
        for (Result rr = scanner.next(); rr != null; rr = scanner.next()) {
            count++;
        }
        Assert.assertTrue(count == 2);
    }
}