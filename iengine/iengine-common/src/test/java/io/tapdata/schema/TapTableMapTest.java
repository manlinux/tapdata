package io.tapdata.schema;

import com.tapdata.entity.task.config.TaskConfig;
import com.tapdata.entity.task.config.TaskRetryConfig;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.pdk.core.utils.RetryUtils;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.internal.verification.Times;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class TapTableMapTest {
    private TapTableMap tapTableMap;
    private Map tableNameAndQualifiedNameMap;
    List<String> tableNames;

    @BeforeEach
    void buildTapTableMap(){
        tapTableMap = mock(TapTableMap.class);
        tableNameAndQualifiedNameMap = new ConcurrentHashMap();
        tableNameAndQualifiedNameMap.put("table1","table1");
        tableNameAndQualifiedNameMap.put("table2","table2");
        tableNameAndQualifiedNameMap.put("table3","table3");
        ReflectionTestUtils.setField(tapTableMap,"tableNameAndQualifiedNameMap",tableNameAndQualifiedNameMap);
        ReflectionTestUtils.setField(tapTableMap,"logger",mock(org.apache.logging.log4j.Logger.class));
        tableNames = new ArrayList<>(tableNameAndQualifiedNameMap.keySet());
    }
    @Nested
    @DisplayName("buildTaskRetryConfig method test")
    class BuildTaskRetryConfigTest{
        @Test
        void testBuildTaskRetryConfigNormal(){
            TaskConfig taskConfig = mock(TaskConfig.class);
            doCallRealMethod().when(tapTableMap).buildTaskRetryConfig(taskConfig);
            tapTableMap.buildTaskRetryConfig(taskConfig);
            Object actual = ReflectionTestUtils.getField(tapTableMap, "taskConfig");
            assertEquals(taskConfig,actual);
        }
        @Test
        void testBuildTaskRetryConfigWithNull(){
            doCallRealMethod().when(tapTableMap).buildTaskRetryConfig(null);
            tapTableMap.buildTaskRetryConfig(null);
            Object actual = ReflectionTestUtils.getField(tapTableMap, "taskConfig");
            assertEquals(null,actual);
        }
    }
    @Nested
    @DisplayName("get method test")
    class TestGetMethod{
        @Test
        @DisplayName("test get without retry")
        void testGetWithoutRetry(){
            when(tapTableMap.getTapTable(any())).thenReturn(null);
            doCallRealMethod().when(tapTableMap).get(any());
            TapTable tapTable = tapTableMap.get(any());
            assertEquals(null, tapTable);
        }
        @Test
        @DisplayName("test get with retry")
        void testGetWithRetry(){
            try (MockedStatic<RetryUtils> mockAutoRetry = Mockito
                    .mockStatic(RetryUtils.class)) {
                mockAutoRetry.when(()->RetryUtils.autoRetry(any(),any())).thenAnswer(invocationOnMock -> {
                    return null;
                });
                TaskConfig taskConfig = mock(TaskConfig.class);
                when(taskConfig.getTaskRetryConfig()).thenReturn(mock(TaskRetryConfig.class));
                ReflectionTestUtils.setField(tapTableMap, "taskConfig",taskConfig);
                doCallRealMethod().when(tapTableMap).get(any());
                tapTableMap.get(any());
                mockAutoRetry.verify(()->RetryUtils.autoRetry(any(),any()));
            }
        }
    }
    @Nested
    @DisplayName("preload schema method test")
    class PreLoadSchemaTest{
        @Test
        @DisplayName("preload has been finished")
        void test1(){
            tapTableMap = spy(new TapTableMap<>("111",1L,tableNameAndQualifiedNameMap));
            doReturn(mock(TapTable.class)).when(tapTableMap).findSchema(anyString());
            tapTableMap.preLoadSchema();
            verify(tapTableMap ,new Times(3)).getTapTable(anyString());
        }
        @Test
        @DisplayName("start thread to preload")
        void test2(){
            tapTableMap = spy(new TapTableMap<>("111",1L,tableNameAndQualifiedNameMap));
            doReturn(1).when(tapTableMap).preLoadSchema(anyList(),anyInt(),any());
            doReturn(mock(TapTable.class)).when(tapTableMap).findSchema(anyString());
            tapTableMap.preLoadSchema();
        }
    }
    @Nested
    @DisplayName("preload schema return index method test")
    class PreLoadSchemaIndexTest{
        @Test
        @DisplayName("test preload schema without interceptor")
        void test1(){
            doCallRealMethod().when(tapTableMap).preLoadSchema(tableNames, 0,null);
            int actual = tapTableMap.preLoadSchema(tableNames,0, null);
            assertEquals(3,actual);
        }
        @Test
        @DisplayName("test preload schema with interceptor")
        void test2(){
            Function<Long, Boolean> costInterceptor = mock(Function.class);
            doCallRealMethod().when(tapTableMap).preLoadSchema(tableNames,0, costInterceptor);
            int actual = tapTableMap.preLoadSchema(tableNames,0,costInterceptor);
            assertEquals(3,actual);
        }
        @Test
        @DisplayName("test preload schema intercept thread")
        void test3(){
            Function<Long, Boolean> costInterceptor = new Function<Long, Boolean>() {
                @Override
                public Boolean apply(Long aLong) {
                    return true;
                }
            };
            doCallRealMethod().when(tapTableMap).preLoadSchema(tableNames,0, costInterceptor);
            int actual = tapTableMap.preLoadSchema(tableNames,0,costInterceptor);
            assertEquals(1,actual);
        }
    }
    @Nested
    @DisplayName("doClose method test")
    class DoCloseTest{
        @Test
        @DisplayName("do close with null future")
        void test1(){
            CompletableFuture future = null;
            ReflectionTestUtils.setField(tapTableMap,"future",future);
            doCallRealMethod().when(tapTableMap).doClose();
            tapTableMap.doClose();
            verify(tapTableMap).doClose();
        }
        @Test
        @DisplayName("do close with future")
        void test2(){
            ExecutorService executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
            ReflectionTestUtils.setField(tapTableMap,"executorService",executorService);
            CompletableFuture future = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }, executorService);
            ReflectionTestUtils.setField(tapTableMap,"future",future);
            doCallRealMethod().when(tapTableMap).doClose();
            tapTableMap.doClose();
            assertEquals(true,future.isCancelled());
        }
    }
    @Nested
    @DisplayName("getTapTable method test")
    class GetTapTableTest{
        @Test
        @DisplayName("test getTapTable normal")
        void testGetTapTableNormal(){
            TapTable tapTable = mock(TapTable.class);
            tapTableMap = spy(new TapTableMap<>("111",1L,tableNameAndQualifiedNameMap));
            doReturn(tapTable).when(tapTableMap).getTapTable("table1");
            TapTable actual = tapTableMap.getTapTable("table1");
            assertEquals(tapTable,actual);
        }
    }
    @Nested
    @DisplayName("reset method test")
    class ResetTest{
        @Test
        void testResetNormal(){
            doCallRealMethod().when(tapTableMap).reset();
            tapTableMap.reset();
            verify(tapTableMap, new Times(1)).doClose();
        }
    }
}
