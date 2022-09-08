package io.tapdata.connector.dameng.cdc.logminer;

import io.tapdata.common.cdc.LogTransaction;
import io.tapdata.connector.dameng.DamengConfig;
import io.tapdata.connector.dameng.DamengContext;
import io.tapdata.connector.dameng.cdc.logminer.bean.OracleInstanceInfo;
import io.tapdata.connector.dameng.cdc.logminer.bean.OracleRedoLogBatch;
import io.tapdata.connector.dameng.cdc.logminer.bean.RedoLog;
import io.tapdata.connector.dameng.cdc.logminer.bean.RedoLogAnalysisBatch;
import io.tapdata.connector.dameng.cdc.logminer.util.JdbcUtil;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.simplify.TapSimplify;
import io.tapdata.entity.utils.cache.KVReadOnlyMap;
import io.tapdata.kit.EmptyKit;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.ValueOut;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.tapdata.connector.dameng.cdc.logminer.constant.OracleSqlConstant.*;

public class ManuRedoOracleLogMiner extends DamengLogMiner {

    private final static String TAG = ManuRedoOracleLogMiner.class.getSimpleName();
    private final int redoLogConcurrency;
    private final LinkedBlockingQueue<AnalysisRedoLogBundle> redoLogBundleForAnalysisQueue;
    private final LinkedBlockingQueue<AnalysisRedoLogBundle> redoLogBundlesForConsume;
    private final ConcurrentHashMap<String, String> cacheRedoLogContent = new ConcurrentHashMap<>();
    private final List<OracleInstanceInfo> oracleInstanceInfos;
    private RedoLogFactory redoLogFactory;
    private boolean isRac;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ExecutorService executorService;
    private final String rootDirectory;

    public AtomicReference<Long> fristLsn;

    private boolean addFile = Boolean.FALSE;

    public ManuRedoOracleLogMiner(DamengContext damengContext, String connectorId) throws Throwable {
        super(damengContext);
        redoLogConcurrency = damengConfig.getConcurrency();
        oracleInstanceInfos = getInstanceInfos();
        redoLogBundleForAnalysisQueue = new LinkedBlockingQueue<>(redoLogConcurrency * 2);
        redoLogBundlesForConsume = new LinkedBlockingQueue<>(redoLogConcurrency * 2);
        scheduledExecutorService = new ScheduledThreadPoolExecutor(1);
        executorService = Executors.newFixedThreadPool(redoLogConcurrency);
        rootDirectory = ((DamengConfig) damengContext.getConfig()).getWorkDir() + File.separator + connectorId;
        Files.deleteIfExists(Paths.get(rootDirectory));
    }

    @Override
    public void init(List<String> tableList, KVReadOnlyMap<TapTable> tableMap, Object offsetState, int recordSize, StreamReadConsumer consumer) throws Throwable {
        super.init(tableList, tableMap, offsetState, recordSize, consumer);
        isRunning.set(true);
        initRedoLogQueueAndThread();
        redoLogFactory = new RedoLogFactory(damengContext, isRunning);
        redoLogFactory.setOracleInstanceInfos(oracleInstanceInfos);
        Long lastScn = damengOffset.getPendingScn() > 0 && damengOffset.getPendingScn() < damengOffset.getLastScn() ? damengOffset.getPendingScn() : damengOffset.getLastScn();
        fristLsn = new AtomicReference<>(0L);
        damengContext.query(String.format(GET_LAST_PROCESS_ARCHIVED_REDO_LOG_FILE_SQL, lastScn, lastScn), archivedResultSet -> {
            if (archivedResultSet.next()) {
                fristLsn.set(archivedResultSet.getLong(1));
            }

        });

        scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (isRunning.get()) {
                try {
                    final OracleRedoLogBatch oracleRedoLogBatch = this.redoLogFactory.produceRedoLog(fristLsn.get());
                    if (oracleRedoLogBatch != null) {
                        StringBuilder sb = new StringBuilder();
                        final List<RedoLog> redoLogList = oracleRedoLogBatch.getRedoLogList();
                        for (RedoLog redoLog : redoLogList) {
                            final long sequence = redoLog.getSequence();
                            sb.append(sequence).append("_");
                        }
                        sb.replace(sb.length() - 1, sb.length(), "");

                        AnalysisRedoLogBundle analysisRedoLogBundle = new AnalysisRedoLogBundle(
                                oracleRedoLogBatch,
                                SingleChronicleQueueBuilder.binary(rootDirectory + File.separator + sb)
                                        .build());

                        while (isRunning.get() && !redoLogBundleForAnalysisQueue.offer(analysisRedoLogBundle, 20, TimeUnit.SECONDS)) {
                            // nothing to do
                            TapLogger.info(TAG, "Redo log queue for analysis is full, redo log {} waiting to enqueue", oracleRedoLogBatch);
                        }

                        while (isRunning.get() && !redoLogBundlesForConsume.offer(analysisRedoLogBundle, 20, TimeUnit.SECONDS)) {
                            // nothing to do
                            TapLogger.info(TAG, "Redo log queue for consume is full, redo log {} waiting to enqueue", oracleRedoLogBatch);
                        }

                    }
                } catch (Exception e) {
                    TapLogger.warn(TAG, "Get redo log failed {}, error stack {}.", e.getMessage());
                }
            }
        }, 1, 5, TimeUnit.SECONDS);
    }

    @Override
    public void startMiner() throws Throwable {
        setSession();
        try {
            executorService.submit(() -> {
                try {
                    while (isRunning.get()) {
                        final AnalysisRedoLogBundle analysisRedoLogBundle;
                        try {
                            analysisRedoLogBundle = redoLogBundleForAnalysisQueue.poll(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            break;
                        }
                        if (analysisRedoLogBundle != null) {
                            final ChronicleQueue redoLogContentQueue = analysisRedoLogBundle.getRedoLogContentQueue();
                            final ExcerptAppender redoLogContentQueueAppender = redoLogContentQueue.acquireAppender();

                            OracleRedoLogBatch oracleRedoLogBatch = analysisRedoLogBundle.getOracleRedoLogBatch();
                            TapLogger.info(TAG, "Starting analysis redo log {}", analysisRedoLogBundle.getOracleRedoLogBatch());

                            doMine(oracleRedoLogBatch,
                                    logData -> enqueue(redoLogContentQueueAppender, logData));

                            // publish finished signal, empty RedoLogContent
                            enqueue(redoLogContentQueueAppender, new HashMap<>());
                        }
                    }
                } catch (Exception ignored) {
                }
            });


            while (isRunning.get()) {

                final AnalysisRedoLogBundle analysisRedoLogBundle = redoLogBundlesForConsume.poll(5, TimeUnit.SECONDS);
                if (analysisRedoLogBundle != null) {

                    final OracleRedoLogBatch oracleRedoLogBatch = analysisRedoLogBundle.getOracleRedoLogBatch();
                    final ChronicleQueue redoLogContentQueue = analysisRedoLogBundle.getRedoLogContentQueue();
                    TapLogger.info(TAG, "Start consume {} logs analysis result.", oracleRedoLogBatch);

                    final ExcerptTailer tailer = redoLogContentQueue.createTailer();
                    while (isRunning.get()) {
                        Map<String, Object> logData = new HashMap<>();
                        boolean success = tailer.readDocument(r -> decodeFromWireIn(r.getValueIn(), logData));

                        if (success) {
                            if (StringUtils.isEmpty((CharSequence) logData.get("OPERATION"))) {
                                TapLogger.info(TAG, "Completed consume redo log {}", oracleRedoLogBatch);
                                redoLogContentQueue.file().delete();
                                redoLogContentQueue.close();
                                break;
                            } else {
                                logDataProcess(
                                        this::sendTransaction,
                                        logData
                                );
                            }
                        }
                    }
                }
            }
        } finally {
            if (EmptyKit.isNotEmpty(redoLogBundleForAnalysisQueue)) {
                for (AnalysisRedoLogBundle analysisRedoLogBundle : redoLogBundleForAnalysisQueue) {
                    final ChronicleQueue redoLogContentQueue = analysisRedoLogBundle.getRedoLogContentQueue();
                    try {
                        redoLogContentQueue.file().delete();
                        redoLogContentQueue.close();
                    } catch (Exception e) {
                        // do nothing
                    }
                }
            }
            Files.deleteIfExists(Paths.get(rootDirectory));
        }
    }

    protected void doMine(OracleRedoLogBatch oracleRedoLogBatch,
                          Consumer<Map<String, Object>> redoLogContentConsumer) throws Exception {
        Long lastScn = damengOffset.getPendingScn() > 0 && damengOffset.getPendingScn() < damengOffset.getLastScn() ? damengOffset.getPendingScn() : damengOffset.getLastScn();
        try {
            List<RedoLog> redoLogList = oracleRedoLogBatch.getRedoLogList();

            List<RedoLogAnalysisBatch> redoLogAnalysisBatches;
            redoLogAnalysisBatches = splitArcRedoLogToMultiBatch(redoLogList);
            Set<String> addedRedoLogNames = new HashSet<>();
            if (EmptyKit.isNotEmpty(redoLogAnalysisBatches)) {
                Collections.sort(redoLogAnalysisBatches);
                String addLogminorSql = createAddLogminorSql(redoLogAnalysisBatches, addedRedoLogNames);
                if (StringUtils.isNotEmpty(addLogminorSql)) {
                    statement.execute(addLogminorSql);
                }

                if (isRunning.get()) {
                    try {
                        statement.execute(String.format(START_LOG_MINOR_CONTINUOUS_MINER_SQL, lastScn));
                    } catch (SQLException e) {
                        throw e;
                    }

                    //get redo log analysis result
                    long startSCN = lastScn;
                    resultSet = statement.executeQuery(analyzeLogSql(startSCN));
                    while (resultSet.next() && isRunning.get()) {
                        while (ddlStop.get()) {
                            TapSimplify.sleep(1000);
                        }
                        ResultSetMetaData metaData = resultSet.getMetaData();
                        Map<String, Object> logData = JdbcUtil.buildLogData(
                                metaData,
                                resultSet,
                                damengConfig.getSysZoneId()
                        );

                        redoLogContentConsumer.accept(logData);
                        if (logData.get("SCN") != null && logData.get("SCN") instanceof BigDecimal) {
                            lastScn = ((BigDecimal) logData.get("SCN")).longValue();
                        }
                    }

                    addFile = false;
                    damengContext.query(String.format(GET_LAST_PROCESS_ARCHIVED_REDO_LOG_FILE_SQL, lastScn, lastScn), archivedResultSet -> {
                        if (archivedResultSet.next()) {
                            fristLsn.set(archivedResultSet.getLong(1));
                        }

                    });
                    statement.execute(END_LOG_MINOR_SQL);

                }

            }

        } catch (SQLRecoverableException se) {
            throw se;
        } catch (InterruptedException e) {
            // abort it
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public String createAddLogminorSql(List<RedoLogAnalysisBatch> redoLogAnalysisBatches, Set<String> addedRedoLogNames) {
        StringBuilder sb = new StringBuilder();
        if (EmptyKit.isNotEmpty(redoLogAnalysisBatches)) {
            for (RedoLogAnalysisBatch redoLogAnalysisBatch : redoLogAnalysisBatches) {
                List<RedoLog> logs = redoLogAnalysisBatch.getRedoLogs();
                if (!EmptyKit.isEmpty(logs)) {
                    for (RedoLog log : logs) {
                        String name = log.getName();
                        if (!addedRedoLogNames.contains(name)) {
                            sb.append(" SYS.dbms_logmnr.add_logfile(logfilename=>'").append(name).append("',options=>SYS.dbms_logmnr.ADDFILE)");
                            addedRedoLogNames.add(name);
                        }
                    }
                }
            }

        }
        return sb.toString();
    }

    private List<RedoLogAnalysisBatch> splitArcRedoLogToMultiBatch(List<RedoLog> redoLogList) {
        List<RedoLogAnalysisBatch> redoLogAnalysisBatches = new ArrayList<>();
                if (EmptyKit.isNotEmpty(redoLogList)) {
                    for (RedoLog redoLog : redoLogList) {
                        long firstChangeScn = redoLog.getFirstChangeScn();
                        RedoLogAnalysisBatch analysisBatch = new RedoLogAnalysisBatch();
                        analysisBatch.setStartSCN(firstChangeScn);
                        analysisBatch.getRedoLogs().add(redoLog);
                        redoLogAnalysisBatches.add(analysisBatch);
                    }
                }

        return redoLogAnalysisBatches;
    }

    private void enqueue(ExcerptAppender redoLogContentQueueAppender, Map<String, Object> logData) {
        redoLogContentQueueAppender.writeDocument(w -> {
            final ValueOut valueOut = w.getValueOut();
            valueOut.writeString(String.valueOf(logData.get("SCN")));
            valueOut.writeString(String.valueOf(logData.get("OPERATION")));
            valueOut.writeString(
                    String.valueOf(
                            logData.get("TIMESTAMP") == null ? null : ((Timestamp) logData.get("TIMESTAMP")).getTime()
                    )
            );

            valueOut.writeString(String.valueOf(logData.get("STATUS")));
            valueOut.writeString(String.valueOf(logData.get("SQL_REDO")));
            valueOut.writeString(String.valueOf(logData.get("SQL_UNDO")));
            valueOut.writeString(String.valueOf(logData.get("ROW_ID")));
            valueOut.writeString(String.valueOf(logData.get("TABLE_NAME")));
            valueOut.writeString(String.valueOf(logData.get("RS_ID")));
            valueOut.writeString(String.valueOf(logData.get("SSN")));
            valueOut.writeString(String.valueOf(logData.get("XID")));
            valueOut.writeString(String.valueOf(logData.get("SEG_OWNER")));
            valueOut.writeString(String.valueOf(logData.get("CSF")));
            valueOut.writeString(String.valueOf(logData.get("UNDO_OPERATION")));
            valueOut.writeString(String.valueOf(logData.get("ROLLBACK")));

            valueOut.writeString(String.valueOf(logData.get("INFO")));
            valueOut.writeString(String.valueOf(logData.get("THREAD#")));
            valueOut.writeString(String.valueOf(logData.get("OPERATION_CODE")));
        });
    }

    private void decodeFromWireIn(ValueIn valueIn, Map<String, Object> logData) {
        final String scnString = valueIn.readString();
        logData.put("SCN",
                nullStringProcess(scnString, () -> 0L, () -> Long.valueOf(scnString))
        );

        final String operationString = valueIn.readString();
        logData.put("OPERATION",
                nullStringProcess(operationString, () -> null, () -> operationString)
        );

        final String timestampString = valueIn.readString();
        logData.put("TIMESTAMP",
                nullStringProcess(timestampString, () -> null, () -> new Timestamp(Long.parseLong(timestampString)))
        );

        final String statusString = valueIn.readString();
        logData.put("STATUS",
                nullStringProcess(statusString, () -> 0L, () -> Long.valueOf(statusString))
        );
        final String sqlRedoString = valueIn.readString();
        logData.put("SQL_REDO",
                nullStringProcess(sqlRedoString, () -> null, () -> sqlRedoString)
        );
        final String sqlUndoString = valueIn.readString();
        logData.put("SQL_UNDO",
                nullStringProcess(sqlUndoString, () -> null, () -> sqlUndoString)
        );
        final String rowIdString = valueIn.readString();
        logData.put("rowId",
                nullStringProcess(rowIdString, () -> null, () -> rowIdString)
        );

        final String tableNameString = valueIn.readString();
        logData.put("TABLE_NAME",
                nullStringProcess(tableNameString, () -> null, () -> tableNameString)
        );

        final String rsIdString = valueIn.readString();
        logData.put("RS_ID",
                nullStringProcess(rsIdString, () -> null, () -> rsIdString)
        );
        final String ssnString = valueIn.readString();
        logData.put("SSN",
                nullStringProcess(ssnString, () -> null, () -> Long.valueOf(ssnString))
        );
        final String xidString = valueIn.readString();
        logData.put("XID",
                nullStringProcess(xidString, () -> null, () -> xidString)
        );
        final String segOwnerString = valueIn.readString();
        logData.put("SEG_OWNER",
                nullStringProcess(segOwnerString, () -> null, () -> segOwnerString)
        );

        final String csfString = valueIn.readString();
        logData.put("CSF",
                nullStringProcess(csfString, () -> 0, () -> Integer.valueOf(csfString))
        );
        final String undoOperationString = valueIn.readString();
        logData.put("UNDO_OPERATION",
                nullStringProcess(undoOperationString, () -> null, () -> undoOperationString)
        );
        final String rollbackString = valueIn.readString();
        logData.put("ROLLBACK",
                nullStringProcess(rollbackString, () -> 0, () -> Integer.valueOf(rollbackString))
        );
        final String infoString = valueIn.readString();
        logData.put("INFO",
                nullStringProcess(infoString, () -> null, () -> infoString)
        );
        final String threadString = valueIn.readString();
        logData.put("THREAD#",
                nullStringProcess(threadString, () -> 0, () -> Integer.valueOf(threadString))
        );
        final String operationCodeString = valueIn.readString();
        logData.put("OPERATION_CODE",
                nullStringProcess(operationCodeString, () -> null, () -> Integer.valueOf(operationCodeString))
        );
    }

    private <T> T nullStringProcess(String inputString, Supplier<T> nullSupplier, Supplier<T> getResult) {
        return "null".equals(inputString) || null == inputString ? nullSupplier.get() : getResult.get();
    }

    protected void logDataProcess(Consumer<Map<String, LogTransaction>> redoLogContentConsumer, Map<String, Object> logData) {
        try {
            analyzeLog(logData);
        } catch (Exception ignored) {

        }
    }


    private List<OracleInstanceInfo> getInstanceInfos() throws Throwable {
        List<OracleInstanceInfo> oracleInstanceInfos = new ArrayList<>();
        damengContext.query(GET_INSTANCES_INFO_SQL, resultSet -> {
            while (resultSet.next()) {
                oracleInstanceInfos.add(new OracleInstanceInfo(resultSet));
            }
        });
        return oracleInstanceInfos;
    }

    @Override
    public void stopMiner() throws Throwable {
        scheduledExecutorService.shutdown();
        executorService.shutdown();
        super.stopMiner();
    }

    static class AnalysisRedoLogBundle {

        private OracleRedoLogBatch oracleRedoLogBatch;

        private final ChronicleQueue redoLogContentQueue;

        public AnalysisRedoLogBundle(OracleRedoLogBatch oracleRedoLogBatch, ChronicleQueue redoLogContentQueue) {
            this.oracleRedoLogBatch = oracleRedoLogBatch;
            this.redoLogContentQueue = redoLogContentQueue;
        }

        public OracleRedoLogBatch getOracleRedoLogBatch() {
            return oracleRedoLogBatch;
        }

        public void setOracleRedoLogBatch(OracleRedoLogBatch oracleRedoLogBatch) {
            this.oracleRedoLogBatch = oracleRedoLogBatch;
        }

        public ChronicleQueue getRedoLogContentQueue() {
            return redoLogContentQueue;
        }
    }
}
