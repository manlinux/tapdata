package io.tapdata.flow.engine.V2.node.hazelcast.data.pdk;

import com.alibaba.fastjson.JSON;
import com.tapdata.constant.BeanUtil;
import com.tapdata.entity.TapdataShareLogEvent;
import com.tapdata.entity.task.context.DataProcessorContext;
import com.tapdata.mongo.ClientMongoOperator;
import com.tapdata.tm.autoinspect.constants.TaskType;
import com.tapdata.tm.autoinspect.dto.TaskAutoInspectResultDto;
import com.tapdata.tm.autoinspect.entity.CompareRecord;
import com.tapdata.tm.autoinspect.entity.CompareTableItem;
import com.tapdata.tm.commons.dag.nodes.AutoInspectNode;
import com.tapdata.tm.commons.dag.vo.SyncObjects;
import com.tapdata.tm.commons.task.dto.TaskDto;
import io.tapdata.autoinspect.compare.IAutoCompare;
import io.tapdata.autoinspect.connector.IPdkConnector;
import io.tapdata.autoinspect.runner.PdkAutoInspectRunner;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.event.dml.TapDeleteRecordEvent;
import io.tapdata.entity.event.dml.TapInsertRecordEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.event.dml.TapUpdateRecordEvent;
import io.tapdata.entity.schema.TapTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author <a href="mailto:harsen_lin@163.com">Harsen</a>
 * @version v1.0 2022/8/17 16:47 Create
 */
public class HazelcastTargetPdkAutoInspectNode extends HazelcastTargetPdkBaseNode {

    private static final Logger logger = LogManager.getLogger(HazelcastTargetPdkAutoInspectNode.class);
    private static final ExecutorService EXECUTORS = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final AtomicBoolean isInitialed = new AtomicBoolean(false);
    private final AtomicLong incrementDelay = new AtomicLong(5 * 1000);//todo: Use task synchronization delay time
    private final LinkedBlockingQueue<List<TapEvent>> incrementQueue = new LinkedBlockingQueue<>(1000);//todo: Optimize the queue, restart task maybe lost events

    public HazelcastTargetPdkAutoInspectNode(DataProcessorContext dataProcessorContext) {
        super(dataProcessorContext);

        if (!(dataProcessorContext.getNode() instanceof AutoInspectNode)) {
            throw new RuntimeException("not AutoInspectNode instance");
        }

        TaskDto task = dataProcessorContext.getTaskDto();
        String taskId = task.getId().toHexString();
        TaskType taskType = TaskType.parseByTaskType(syncType.getSyncType());
        AutoInspectNode node = (AutoInspectNode) dataProcessorContext.getNode();
        ClientMongoOperator clientMongoOperator = BeanUtil.getBean(ClientMongoOperator.class);

        HazelcastTargetPdkAutoInspectNode _thisNode = this;
        EXECUTORS.submit(new PdkAutoInspectRunner(taskId, taskType, clientMongoOperator, node.getFromNode(), node.getToNode()) {
            @Override
            protected void initialCompare(IPdkConnector sourceConnector, IPdkConnector targetConnector, IAutoCompare autoCompare) throws Exception {
                logger.info("begin initial compare");

                long beginTimes = System.currentTimeMillis();
                while (true) {
                    if (!isRunning()) return;
                    if (isInitialed.get()) break;

                    Thread.sleep(1000L);
                }
                logger.info("wait initial compare {}ms", System.currentTimeMillis() - beginTimes);

                TapTable tapTable;
                for (SyncObjects syncObjects : node.getSyncObjects()) {
                    if (!"table".equals(syncObjects.getType())) continue;

                    for (String tableName : syncObjects.getObjectNames()) {
                        if (!isRunning()) return;

                        tapTable = sourceConnector.getTapTable(tableName);
                        if (null == tapTable.primaryKeys() || tapTable.primaryKeys().isEmpty()) {
                            logger.warn("ignore non primary key table: {}", tableName);
                            continue;
                        }
                        tapTable = targetConnector.getTapTable(tableName);
                        if (null == tapTable.primaryKeys() || tapTable.primaryKeys().isEmpty()) {
                            logger.warn("ignore non primary key table: {}", tableName);
                            continue;
                        }

                        initialCompare(sourceConnector, targetConnector, autoCompare, new CompareTableItem(tableName));
                    }
                }
            }

            @Override
            protected void incrementalCompare(IPdkConnector sourceConnector, IPdkConnector targetConnector, IAutoCompare autoCompare) throws Exception {
                List<TapEvent> tapEvents;
                while (isRunning()) {
                    do {
                        tapEvents = incrementQueue.poll(1, TimeUnit.SECONDS);
                        if (!isRunning()) return;

                        //Output log every 30 seconds
                        if (System.currentTimeMillis()/1000 % 30 == 0) {
                            logger.info("Increment event is empty");
                        }
                    } while (null == tapEvents);

                    CompareRecord sourceRecord;
                    for (TapEvent tapEvent : tapEvents) {
                        if (tapEvent instanceof TapRecordEvent) {
                            String tableName = ((TapRecordEvent) tapEvent).getTableId();
                            TapTable tapTable = sourceConnector.getTapTable(tableName);

                            Long eventDelay = Optional.ofNullable(((TapRecordEvent) tapEvent).getReferenceTime()).map(times -> {
                                if (times < 0) {
                                    logger.warn("Incremental event time is negative: {}", JSON.toJSONString(tapEvent));
                                    return 0L;
                                }
                                // not larger 5 minus
                                return Math.min(incrementDelay.get() - times, 5 * 60 * 1000L);
                            }).orElse(0L);
                            // not less 0
                            if (eventDelay > 0) {
                                logger.warn("Incremental delay times: {}", eventDelay);
                                Thread.sleep(eventDelay);
                            }

                            if (tapEvent instanceof TapUpdateRecordEvent) {
                                //update event
                                sourceRecord = toCompareEvent(sourceConnector.getConnId(), tapTable, ((TapUpdateRecordEvent) tapEvent).getAfter());
                            } else if (tapEvent instanceof TapInsertRecordEvent) {
                                //insert event
                                sourceRecord = toCompareEvent(sourceConnector.getConnId(), tapTable, ((TapInsertRecordEvent) tapEvent).getAfter());
                            } else if (tapEvent instanceof TapDeleteRecordEvent) {
                                //delete event
                                sourceRecord = toCompareEvent(sourceConnector.getConnId(), tapTable, ((TapDeleteRecordEvent) tapEvent).getBefore());
                            } else {
                                //no support events
                                continue;
                            }

                            LinkedHashMap<String, Object> keymap = new LinkedHashMap<>();
                            for (String k : sourceRecord.getKeyNames()) {
                                keymap.put(k, sourceRecord.getDataValue(k));
                            }

                            CompareRecord targetRecord = targetConnector.queryByKey(tableName, keymap);
                            if (tapEvent instanceof TapDeleteRecordEvent) {
                                if (null != targetRecord) {
                                    targetRecord.getOriginalKey().putAll(sourceRecord.getOriginalKey());
                                    //target has more data
                                    autoCompare.add(new TaskAutoInspectResultDto(taskId, sourceConnector.getConnId(), sourceRecord.getTableName(), keymap, null, targetConnector.getConnId(), targetRecord.getTableName(), targetRecord.getData()));
                                }
                            } else if (null == targetRecord) {
                                //source has more data
                                autoCompare.add(new TaskAutoInspectResultDto(taskId, sourceConnector.getConnId(), sourceRecord.getTableName(), keymap, sourceRecord.getData(), targetConnector.getConnId(), tableName, null));
                            } else {
                                targetRecord.getOriginalKey().putAll(sourceRecord.getOriginalKey());
                                switch (compare(sourceRecord, targetRecord)) {
                                    case MoveTarget:
                                    case MoveSource:
                                        logger.warn("diff data keys: {}, {}", JSON.toJSONString(sourceRecord.getData()), JSON.toJSONString(targetRecord.getData()));
                                        break;
                                    case Diff:
                                        autoCompare.add(new TaskAutoInspectResultDto(taskId, sourceConnector.getConnId(), sourceRecord.getTableName(), keymap, sourceRecord.getData(), targetConnector.getConnId(), targetRecord.getTableName(), targetRecord.getData()));
                                        break;
                                    default:
                                        break;
                                }
                            }
                        }
                    }
                }

            }

            @Override
            protected boolean isRunning() {
                return _thisNode.isRunning() && !Thread.interrupted();
            }

            @Override
            protected boolean isStopping() {
                return false;
            }
        });
    }

    @Override
    protected void handleTapdataCompleteSnapshotEvent() {
        super.handleTapdataCompleteSnapshotEvent();
        isInitialed.set(true);
    }

    @Override
    void processEvents(List<TapEvent> tapEvents) {
        if (null == tapEvents) return;
        if (!isInitialed.get()) return;

        while (isRunning()) {
            try {
                if (incrementQueue.offer(tapEvents, 100, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    void processShareLog(List<TapdataShareLogEvent> tapdataShareLogEvents) {
        if (null == tapdataShareLogEvents) return;

        tapdataShareLogEvents.forEach(tapEvent -> {
            logger.info("AutoInspect TapdataShareLogEvent: {}", JSON.toJSONString(tapEvent));
        });
    }

    private CompareRecord toCompareEvent(ObjectId connectionId, TapTable tapTable, Map<String, Object> data) {
        LinkedHashMap<String, Object> keymap = new LinkedHashMap<>();
        for (String pk : tapTable.primaryKeys()) {
            keymap.put(pk, data.get(pk));
        }

        return new CompareRecord(tapTable.getName(), connectionId, keymap, new LinkedHashSet<>(keymap.keySet()), data);
    }

}