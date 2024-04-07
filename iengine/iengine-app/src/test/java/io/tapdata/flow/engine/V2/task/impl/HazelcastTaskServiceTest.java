package io.tapdata.flow.engine.V2.task.impl;

import com.tapdata.constant.ConfigurationCenter;
import com.tapdata.entity.Connections;
import com.tapdata.entity.DatabaseTypeEnum;
import com.tapdata.entity.task.config.TaskConfig;
import com.tapdata.entity.task.config.TaskRetryConfig;
import com.tapdata.mongo.HttpClientMongoOperator;
import com.tapdata.tm.commons.dag.Edge;
import com.tapdata.tm.commons.dag.Node;
import com.tapdata.tm.commons.dag.nodes.DataParentNode;
import com.tapdata.tm.commons.dag.nodes.TableNode;
import com.tapdata.tm.commons.dag.process.MergeTableNode;
import com.tapdata.tm.commons.dag.process.ProcessorNode;
import com.tapdata.tm.commons.dag.vo.ReadPartitionOptions;
import com.tapdata.tm.commons.task.dto.TaskDto;
import io.tapdata.common.SettingService;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.flow.engine.V2.node.hazelcast.HazelcastBaseNode;
import io.tapdata.flow.engine.V2.node.hazelcast.data.HazelcastBlank;
import io.tapdata.flow.engine.V2.node.hazelcast.data.pdk.HazelcastPdkSourceAndTargetTableNode;
import io.tapdata.flow.engine.V2.node.hazelcast.data.pdk.HazelcastSourcePartitionReadDataNode;
import io.tapdata.flow.engine.V2.node.hazelcast.data.pdk.HazelcastSourcePdkDataNode;
import io.tapdata.schema.TapTableMap;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class HazelcastTaskServiceTest {
    @Nested
    class GetTaskRetryConfigTest{
        private HazelcastTaskService hazelcastTaskService;
        @BeforeEach
        void setUp(){
            HttpClientMongoOperator clientMongoOperator = mock(HttpClientMongoOperator.class);
            hazelcastTaskService = spy(new HazelcastTaskService(clientMongoOperator));
        }
        @DisplayName("test get task retry config default")
        @Test
        void test1(){
            SettingService settingService = mock(SettingService.class);
            when(settingService.getLong(eq("retry_interval_second"), eq(60L))).thenReturn(60L);
            when(settingService.getLong(eq("max_retry_time_minute"), eq(60L))).thenReturn(60L);
            ReflectionTestUtils.setField(hazelcastTaskService, "settingService", settingService);
            TaskRetryConfig taskRetryConfig = hazelcastTaskService.getTaskRetryConfig();
            assertEquals(60L, taskRetryConfig.getRetryIntervalSecond());
            assertEquals(60L * 60, taskRetryConfig.getMaxRetryTimeSecond());
        }
    }
    @Nested
    class CreateNodeTest{
        private TaskDto taskDto;
        private List<Node> nodes;
        private List<Edge> edges;
        private Node node;
        private List<Node> predecessors;
        private List<Node> successors;
        private ConfigurationCenter config;
        private Connections connection;
        private DatabaseTypeEnum.DatabaseType databaseType;
        private Map<String, MergeTableNode> mergeTableMap;
        private TapTableMap<String, TapTable> tapTableMap;
        private TaskConfig taskConfig;
        @BeforeEach
        void beforeEach(){
            taskDto = mock(TaskDto.class);
            nodes = new ArrayList<>();
            edges = new ArrayList<>();
            node = mock(TableNode.class);
            predecessors = new ArrayList<>();
            successors = new ArrayList<>();
            config = mock(ConfigurationCenter.class);
            config = new ConfigurationCenter();
            config.putConfig(ConfigurationCenter.RETRY_TIME, 1);
            connection = mock(Connections.class);
            databaseType = mock(DatabaseTypeEnum.DatabaseType.class);
            mergeTableMap = new HashMap<>();
            tapTableMap = mock(TapTableMap.class);
            taskConfig = mock(TaskConfig.class);
        }
        @Test
        @SneakyThrows
        @DisplayName("test createNode method when read partition option is enable")
        void testCreateNode1(){
            when(node.getType()).thenReturn("table");
            successors.add(mock(Node.class));
            when(connection.getPdkType()).thenReturn("pdk");
            ReadPartitionOptions readPartitionOptions = mock(ReadPartitionOptions.class);
            when(((DataParentNode)node).getReadPartitionOptions()).thenReturn(readPartitionOptions);
            when(readPartitionOptions.isEnable()).thenReturn(true);
            when(readPartitionOptions.getSplitType()).thenReturn(10);
            when(taskDto.getType()).thenReturn("initial_sync");
            HazelcastBaseNode actual = HazelcastTaskService.createNode(taskDto, nodes, edges, node, predecessors, successors, config, connection, databaseType, mergeTableMap, tapTableMap, taskConfig);
            assertEquals(HazelcastSourcePartitionReadDataNode.class, actual.getClass());
        }
        @Test
        @SneakyThrows
        @DisplayName("test createNode method when read partition option is enable and task type is cdc")
        void testCreateNode2(){
            when(node.getType()).thenReturn("table");
            successors.add(mock(Node.class));
            when(connection.getPdkType()).thenReturn("pdk");
            ReadPartitionOptions readPartitionOptions = mock(ReadPartitionOptions.class);
            when(((DataParentNode)node).getReadPartitionOptions()).thenReturn(readPartitionOptions);
            when(readPartitionOptions.isEnable()).thenReturn(true);
            when(readPartitionOptions.getSplitType()).thenReturn(10);
            when(taskDto.getType()).thenReturn("cdc");
            HazelcastBaseNode actual = HazelcastTaskService.createNode(taskDto, nodes, edges, node, predecessors, successors, config, connection, databaseType, mergeTableMap, tapTableMap, taskConfig);
            assertEquals(HazelcastSourcePdkDataNode.class, actual.getClass());
        }
        @Test
        @SneakyThrows
        @DisplayName("test createNode method for hazelcast blank")
        void testCreateNode3(){
            node = mock(ProcessorNode.class);
            when(node.disabledNode()).thenReturn(true);
            HazelcastBaseNode actual = HazelcastTaskService.createNode(taskDto, nodes, edges, node, predecessors, successors, config, connection, databaseType, mergeTableMap, tapTableMap, taskConfig);
            assertEquals(HazelcastBlank.class, actual.getClass());
        }
        @Test
        @SneakyThrows
        @DisplayName("test createNode method for hazelcast pdk source and target table node")
        void testCreateNode4(){
            when(node.getType()).thenReturn("table");
            predecessors.add(mock(Node.class));
            successors.add(mock(Node.class));
            when(connection.getPdkType()).thenReturn("pdk");
            when(taskDto.getType()).thenReturn("initial_sync");
            HazelcastBaseNode actual = HazelcastTaskService.createNode(taskDto, nodes, edges, node, predecessors, successors, config, connection, databaseType, mergeTableMap, tapTableMap, taskConfig);
            assertEquals(HazelcastPdkSourceAndTargetTableNode.class, actual.getClass());
        }
    }
}
