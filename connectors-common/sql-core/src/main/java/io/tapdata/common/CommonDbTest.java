package io.tapdata.common;

import io.tapdata.constant.ConnectionTypeEnum;
import io.tapdata.constant.DbTestItem;
import io.tapdata.kit.EmptyKit;
import io.tapdata.pdk.apis.entity.TestItem;
import io.tapdata.pdk.apis.functions.connection.ConnectionCheckItem;
import io.tapdata.util.NetUtil;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.tapdata.base.ConnectorBase.testItem;

public abstract class CommonDbTest implements AutoCloseable {

    protected CommonDbConfig commonDbConfig;
    protected JdbcContext jdbcContext;
    protected Consumer<TestItem> consumer;
    protected Map<String, Supplier<Boolean>> testFunctionMap;
    protected final String uuid = UUID.randomUUID().toString();
    private static final String TEST_HOST_PORT_MESSAGE = "connected to %s:%s succeed!";
    private static final String TEST_CONNECTION_LOGIN = "login succeed!";
    private static final String TEST_WRITE_TABLE = "tapdata___test";

    public CommonDbTest() {

    }

    public CommonDbTest(CommonDbConfig commonDbConfig, Consumer<TestItem> consumer) {
        this.commonDbConfig = commonDbConfig;
        this.consumer = consumer;
        testFunctionMap = new LinkedHashMap<>();
        testFunctionMap.put("testHostPort", this::testHostPort);
        testFunctionMap.put("testConnect", this::testConnect);
        testFunctionMap.put("testVersion", this::testVersion);
        if (!ConnectionTypeEnum.SOURCE.getType().equals(commonDbConfig.get__connectionType())) {
            testFunctionMap.put("testWritePrivilege", this::testWritePrivilege);
        }
        if (!ConnectionTypeEnum.TARGET.getType().equals(commonDbConfig.get__connectionType())) {
            testFunctionMap.put("testReadPrivilege", this::testReadPrivilege);
            testFunctionMap.put("testStreamRead", this::testStreamRead);
        }
    }

    public Boolean testOneByOne() {
        for (Map.Entry<String, Supplier<Boolean>> entry : testFunctionMap.entrySet()) {
            Boolean res = entry.getValue().get();
            if (EmptyKit.isNull(res) || !res) {
                return false;
            }
        }
        return true;
    }

    //Test host and port
    protected Boolean testHostPort() {
        try {
            NetUtil.validateHostPortWithSocket(commonDbConfig.getHost(), commonDbConfig.getPort());
            consumer.accept(testItem(DbTestItem.HOST_PORT.getContent(), TestItem.RESULT_SUCCESSFULLY,
                    String.format(TEST_HOST_PORT_MESSAGE, commonDbConfig.getHost(), commonDbConfig.getPort())));
            return true;
        } catch (IOException e) {
            consumer.accept(testItem(DbTestItem.HOST_PORT.getContent(), TestItem.RESULT_FAILED, e.getMessage()));
            return false;
        }
    }

    //Test connect and log in
    protected Boolean testConnect() {
        try (
                Connection connection = jdbcContext.getConnection()
        ) {
            consumer.accept(testItem(TestItem.ITEM_CONNECTION, TestItem.RESULT_SUCCESSFULLY, TEST_CONNECTION_LOGIN));
            return true;
        } catch (Exception e) {
            consumer.accept(testItem(TestItem.ITEM_CONNECTION, TestItem.RESULT_FAILED, e.getMessage()));
            return false;
        }
    }

    protected Boolean testVersion() {
        try (
                Connection connection = jdbcContext.getConnection()
        ) {
            DatabaseMetaData databaseMetaData = connection.getMetaData();
            String versionStr = databaseMetaData.getDatabaseProductName() + " " +
                    databaseMetaData.getDatabaseMajorVersion() + "." + databaseMetaData.getDatabaseMinorVersion();
            consumer.accept(testItem(TestItem.ITEM_VERSION, TestItem.RESULT_SUCCESSFULLY, versionStr));
        } catch (Exception e) {
            consumer.accept(testItem(TestItem.ITEM_VERSION, TestItem.RESULT_FAILED, e.getMessage()));
        }
        return true;
    }

    private static final String TEST_CREATE_TABLE = "create table %s(col1 int)";
    private static final String TEST_WRITE_RECORD = "insert into %s values(0)";
    private static final String TEST_UPDATE_RECORD = "update %s set col1=1 where 1=1";
    private static final String TEST_DELETE_RECORD = "delete from %s where 1=1";
    private static final String TEST_DROP_TABLE = "drop table %s";
    private static final String TEST_WRITE_SUCCESS = "Create,Insert,Update,Delete,Drop succeed";

    protected Boolean testWritePrivilege() {
        try {
            List<String> sqls = new ArrayList<>();
            if (jdbcContext.queryAllTables(Arrays.asList(TEST_WRITE_TABLE, TEST_WRITE_TABLE.toUpperCase())).size() > 0) {
                sqls.add(String.format(TEST_DROP_TABLE, TEST_WRITE_TABLE));
            }
            //create
            sqls.add(String.format(TEST_CREATE_TABLE, TEST_WRITE_TABLE));
            //insert
            sqls.add(String.format(TEST_WRITE_RECORD, TEST_WRITE_TABLE));
            //update
            sqls.add(String.format(TEST_UPDATE_RECORD, TEST_WRITE_TABLE));
            //delete
            sqls.add(String.format(TEST_DELETE_RECORD, TEST_WRITE_TABLE));
            //drop
            sqls.add(String.format(TEST_DROP_TABLE, TEST_WRITE_TABLE));
            jdbcContext.batchExecute(sqls);
            consumer.accept(testItem(TestItem.ITEM_WRITE, TestItem.RESULT_SUCCESSFULLY, TEST_WRITE_SUCCESS));
        } catch (Exception e) {
            consumer.accept(testItem(TestItem.ITEM_WRITE, TestItem.RESULT_FAILED, e.getMessage()));
        }
        return true;
    }

    public abstract Boolean testReadPrivilege();

    public abstract Boolean testStreamRead();

    //healthCheck-ping
    public ConnectionCheckItem testPing() {
        long start = System.currentTimeMillis();
        ConnectionCheckItem connectionCheckItem = ConnectionCheckItem.create();
        connectionCheckItem.item(ConnectionCheckItem.ITEM_PING);
        try {
            NetUtil.validateHostPortWithSocket(commonDbConfig.getHost(), commonDbConfig.getPort());
            connectionCheckItem.result(ConnectionCheckItem.RESULT_SUCCESSFULLY);
        } catch (IOException e) {
            connectionCheckItem.result(ConnectionCheckItem.RESULT_FAILED).information(e.getMessage());
        }
        connectionCheckItem.takes(System.currentTimeMillis() - start);
        return connectionCheckItem;
    }

    //healthCheck-connection
    public ConnectionCheckItem testConnection() {
        long start = System.currentTimeMillis();
        ConnectionCheckItem connectionCheckItem = ConnectionCheckItem.create();
        connectionCheckItem.item(ConnectionCheckItem.ITEM_CONNECTION);
        try (
                Connection connection = jdbcContext.getConnection()
        ) {
            connectionCheckItem.result(ConnectionCheckItem.RESULT_SUCCESSFULLY);
        } catch (Exception e) {
            connectionCheckItem.result(ConnectionCheckItem.RESULT_FAILED).information(e.getMessage());
        }
        connectionCheckItem.takes(System.currentTimeMillis() - start);
        return connectionCheckItem;
    }

    @Override
    public void close() {
        try {
            jdbcContext.finish(uuid);
        } catch (Exception ignored) {
        }
    }

}
