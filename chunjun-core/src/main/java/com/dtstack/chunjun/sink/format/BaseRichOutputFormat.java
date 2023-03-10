/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.chunjun.sink.format;

import com.dtstack.chunjun.cdc.DdlRowData;
import com.dtstack.chunjun.cdc.config.DDLConfig;
import com.dtstack.chunjun.cdc.exception.LogExceptionHandler;
import com.dtstack.chunjun.cdc.handler.DDLHandler;
import com.dtstack.chunjun.cdc.utils.ExecutorUtils;
import com.dtstack.chunjun.config.CommonConfig;
import com.dtstack.chunjun.constants.Metrics;
import com.dtstack.chunjun.converter.AbstractRowConverter;
import com.dtstack.chunjun.dirty.DirtyConfig;
import com.dtstack.chunjun.dirty.manager.DirtyManager;
import com.dtstack.chunjun.dirty.utils.DirtyConfUtil;
import com.dtstack.chunjun.enums.Semantic;
import com.dtstack.chunjun.factory.ChunJunThreadFactory;
import com.dtstack.chunjun.metrics.AccumulatorCollector;
import com.dtstack.chunjun.metrics.BaseMetric;
import com.dtstack.chunjun.metrics.RowSizeCalculator;
import com.dtstack.chunjun.restore.FormatState;
import com.dtstack.chunjun.throwable.ChunJunRuntimeException;
import com.dtstack.chunjun.throwable.NoRestartException;
import com.dtstack.chunjun.throwable.WriteRecordException;
import com.dtstack.chunjun.util.DataSyncFactoryUtil;
import com.dtstack.chunjun.util.ExceptionUtil;
import com.dtstack.chunjun.util.JsonUtil;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.accumulators.LongCounter;
import org.apache.flink.api.common.io.CleanupWhenUnsuccessful;
import org.apache.flink.api.common.io.FinalizeOnMaster;
import org.apache.flink.api.common.io.InitializeOnMaster;
import org.apache.flink.api.common.io.RichOutputFormat;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.table.data.RowData;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract Specification for all the OutputFormat defined in chunjun plugins
 *
 * <p>NOTE Four situations for checkpoint(cp): 1).Turn off cp, batch and timing directly submitted
 * to the database
 *
 * <p>2).Turn on cp and in AT_LEAST_ONCE model, batch and timing directly commit to the db .
 * snapshotState???notifyCheckpointComplete???notifyCheckpointAborted Does not interact with the db
 *
 * <p>3).Turn on cp and in EXACTLY_ONCE model, batch and timing pre commit to the db . snapshotState
 * pre commit???notifyCheckpointComplete real commit???notifyCheckpointAborted rollback
 *
 * <p>4).Turn on cp and in EXACTLY_ONCE model, when cp time out
 * snapshotState???notifyCheckpointComplete may never call, Only call notifyCheckpointAborted.this
 * maybe a problem ,should make users perceive
 */
@Slf4j
public abstract class BaseRichOutputFormat extends RichOutputFormat<RowData>
        implements CleanupWhenUnsuccessful, InitializeOnMaster, FinalizeOnMaster {

    private static final long serialVersionUID = -5787516937092596610L;

    public static final int LOG_PRINT_INTERNAL = 2000;

    /** ??????????????? */
    protected StreamingRuntimeContext context;
    /** ???????????????checkpoint */
    protected boolean checkpointEnabled;

    /** ???????????? */
    protected String jobName = "defaultJobName";
    /** ??????id */
    protected String jobId;
    /** ????????????id */
    protected int taskNumber;
    /** ??????????????? */
    protected int numTasks;
    /** ??????????????????, openInputFormat()???????????? */
    protected long startTime;

    protected String formatId;
    /** checkpoint????????????map */
    protected FormatState formatState;

    /** ????????????cp??????????????????????????????????????????????????????????????? EXACTLY_ONCE??????????????????????????????????????? AT_LEAST_ONCE???????????????????????????????????????????????????????????? */
    protected CheckpointingMode checkpointMode;
    /** ???????????????????????? */
    protected transient ScheduledExecutorService scheduler;
    /** ???????????????????????????????????? */
    protected transient ScheduledFuture<?> scheduledFuture;
    /** ??????????????????????????????????????????????????? */
    protected long flushIntervalMills;
    /** ?????????????????? */
    protected CommonConfig config;
    /** BaseRichOutputFormat???????????? */
    protected transient volatile boolean closed = false;
    /** ?????????????????? */
    protected int batchSize = 1;
    /** ????????????????????? */
    protected RowData lastRow = null;

    /** ????????????????????????????????? */
    protected transient List<RowData> rows;
    /** ????????????????????? */
    protected AbstractRowConverter rowConverter;
    /** ?????????????????????????????????????????????????????????hive????????????????????????false */
    protected boolean initAccumulatorAndDirty = true;
    /** ??????????????? */
    protected transient BaseMetric outputMetric;
    /** cp???flush???????????? */
    protected transient AtomicBoolean flushEnable;
    /** ????????????????????? */
    protected long rowsOfCurrentTransaction;

    /** A collection of field names filled in user scripts with constants removed */
    protected List<String> columnNameList = new ArrayList<>();
    /** A collection of field types filled in user scripts with constants removed */
    protected List<String> columnTypeList = new ArrayList<>();

    /** ?????????????????? */
    protected AccumulatorCollector accumulatorCollector;
    /** ????????????????????? */
    protected RowSizeCalculator rowSizeCalculator;

    protected LongCounter bytesWriteCounter;
    protected LongCounter durationCounter;
    protected LongCounter numWriteCounter;
    protected LongCounter snapshotWriteCounter;
    protected LongCounter errCounter;
    protected LongCounter nullErrCounter;
    protected LongCounter duplicateErrCounter;
    protected LongCounter conversionErrCounter;
    protected LongCounter otherErrCounter;

    protected Semantic semantic;

    /** the manager of dirty data. */
    protected DirtyManager dirtyManager;

    /** ????????????ddl?????? * */
    protected boolean executeDdlAble;

    protected DDLConfig ddlConfig;

    protected DDLHandler ddlHandler;

    protected ExecutorService executorService;

    protected boolean useAbstractColumn;

    private transient volatile Exception timerWriteException;

    @Override
    public void initializeGlobal(int parallelism) {
        // ???????????????????????????configure????????????
    }

    @Override
    public void configure(Configuration parameters) {
        // do nothing
    }

    @Override
    public void finalizeGlobal(int parallelism) {
        // ????????????????????????
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param taskNumber ????????????id
     * @param numTasks ???????????????
     * @throws IOException
     */
    @Override
    public void open(int taskNumber, int numTasks) throws IOException {
        this.taskNumber = taskNumber;
        this.numTasks = numTasks;
        this.context = (StreamingRuntimeContext) getRuntimeContext();
        this.checkpointEnabled = context.isCheckpointingEnabled();
        this.batchSize = config.getBatchSize();
        this.rows = new ArrayList<>(batchSize);
        this.executeDdlAble = config.isExecuteDdlAble();
        if (executeDdlAble) {
            ddlHandler = DataSyncFactoryUtil.discoverDdlHandler(ddlConfig);
            try {
                ddlHandler.init(ddlConfig.getProperties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            executorService =
                    ExecutorUtils.threadPoolExecutor(
                            2,
                            10,
                            0,
                            Integer.MAX_VALUE,
                            "ddl-executor-pool-%d",
                            true,
                            new LogExceptionHandler());
        }
        this.flushIntervalMills = config.getFlushIntervalMills();
        this.flushEnable = new AtomicBoolean(true);
        this.semantic = Semantic.getByName(config.getSemantic());

        ExecutionConfig.GlobalJobParameters params =
                context.getExecutionConfig().getGlobalJobParameters();
        DirtyConfig dc = DirtyConfUtil.parseFromMap(params.toMap());
        this.dirtyManager = new DirtyManager(dc, this.context);

        checkpointMode =
                context.getCheckpointMode() == null
                        ? CheckpointingMode.AT_LEAST_ONCE
                        : context.getCheckpointMode();

        Map<String, String> vars = context.getMetricGroup().getAllVariables();
        if (vars != null) {
            jobName = vars.getOrDefault(Metrics.JOB_NAME, "defaultJobName");
            jobId = vars.get(Metrics.JOB_ID);
        }

        initStatisticsAccumulator();
        initRestoreInfo();
        initTimingSubmitTask();
        initRowSizeCalculator();

        if (initAccumulatorAndDirty) {
            initAccumulatorCollector();
        }
        openInternal(taskNumber, numTasks);
        this.startTime = System.currentTimeMillis();

        log.info(
                "[{}] open successfully, \ncheckpointMode = {}, \ncheckpointEnabled = {}, \nflushIntervalMills = {}, \nbatchSize = {}, \n[{}]: \n{} ",
                this.getClass().getSimpleName(),
                checkpointMode,
                checkpointEnabled,
                flushIntervalMills,
                batchSize,
                config.getClass().getSimpleName(),
                JsonUtil.toPrintJson(config));
    }

    @Override
    public synchronized void writeRecord(RowData rowData) {
        checkTimerWriteException();
        int size = 0;
        if (rowData instanceof DdlRowData) {
            executeDdlRowDataTemplate((DdlRowData) rowData);
            size = 1;
        } else {
            if (batchSize <= 1) {
                writeSingleRecord(rowData, numWriteCounter);
                size = 1;
            } else {
                rows.add(rowData);
                if (rows.size() >= batchSize) {
                    writeRecordInternal();
                    size = batchSize;
                }
            }
        }
        updateDuration();
        bytesWriteCounter.add(rowSizeCalculator.getObjectSize(rowData));
        if (checkpointEnabled) {
            snapshotWriteCounter.add(size);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        log.info("taskNumber[{}] close()", taskNumber);

        if (closed) {
            return;
        }

        if (Objects.isNull(rows)) {
            return;
        }

        Exception closeException = null;

        if (null != timerWriteException) {
            closeException = timerWriteException;
        }

        // when exist data
        int size = rows.size();
        if (size != 0) {
            try {
                writeRecordInternal();
                numWriteCounter.add(size);
            } catch (Exception e) {
                closeException = e;
            }
        }

        if (this.scheduledFuture != null) {
            scheduledFuture.cancel(false);
            this.scheduler.shutdown();
        }

        try {
            closeInternal();
        } catch (Exception e) {
            log.warn("closeInternal() Exception:{}", ExceptionUtil.getErrorMessage(e));
        }

        updateDuration();

        if (outputMetric != null) {
            outputMetric.waitForReportMetrics();
        }

        if (accumulatorCollector != null) {
            accumulatorCollector.close();
        }

        if (dirtyManager != null) {
            dirtyManager.close();
        }

        if (closeException != null) {
            throw new RuntimeException(closeException);
        }

        log.info("subtask[{}}] close() finished", taskNumber);
        this.closed = true;
    }

    @Override
    public void tryCleanupOnError() throws Exception {}

    /** ???????????????????????? */
    protected void initStatisticsAccumulator() {
        errCounter = context.getLongCounter(Metrics.NUM_ERRORS);
        nullErrCounter = context.getLongCounter(Metrics.NUM_NULL_ERRORS);
        duplicateErrCounter = context.getLongCounter(Metrics.NUM_DUPLICATE_ERRORS);
        conversionErrCounter = context.getLongCounter(Metrics.NUM_CONVERSION_ERRORS);
        otherErrCounter = context.getLongCounter(Metrics.NUM_OTHER_ERRORS);
        numWriteCounter = context.getLongCounter(Metrics.NUM_WRITES);
        snapshotWriteCounter = context.getLongCounter(Metrics.SNAPSHOT_WRITES);
        bytesWriteCounter = context.getLongCounter(Metrics.WRITE_BYTES);
        durationCounter = context.getLongCounter(Metrics.WRITE_DURATION);

        outputMetric = new BaseMetric(context);
        outputMetric.addMetric(Metrics.NUM_ERRORS, errCounter);
        outputMetric.addMetric(Metrics.NUM_NULL_ERRORS, nullErrCounter);
        outputMetric.addMetric(Metrics.NUM_DUPLICATE_ERRORS, duplicateErrCounter);
        outputMetric.addMetric(Metrics.NUM_CONVERSION_ERRORS, conversionErrCounter);
        outputMetric.addMetric(Metrics.NUM_OTHER_ERRORS, otherErrCounter);
        outputMetric.addMetric(Metrics.NUM_WRITES, numWriteCounter, true);
        outputMetric.addMetric(Metrics.SNAPSHOT_WRITES, snapshotWriteCounter);
        outputMetric.addMetric(Metrics.WRITE_BYTES, bytesWriteCounter, true);
        outputMetric.addMetric(Metrics.WRITE_DURATION, durationCounter);
        outputMetric.addDirtyMetric(
                Metrics.DIRTY_DATA_COUNT, this.dirtyManager.getConsumedMetric());
        outputMetric.addDirtyMetric(
                Metrics.DIRTY_DATA_COLLECT_FAILED_COUNT,
                this.dirtyManager.getFailedConsumedMetric());
    }

    /** ??????????????????????????? */
    private void initAccumulatorCollector() {
        accumulatorCollector = new AccumulatorCollector(context, Metrics.METRIC_SINK_LIST);
        accumulatorCollector.start();
    }

    /** ?????????????????????????????? */
    protected void initRowSizeCalculator() {
        rowSizeCalculator =
                RowSizeCalculator.getRowSizeCalculator(
                        config.getRowSizeCalculatorType(), useAbstractColumn);
    }

    /** ???checkpoint????????????map???????????????????????????????????? */
    private void initRestoreInfo() {
        if (formatState == null) {
            formatState = new FormatState(taskNumber, null);
        } else {
            errCounter.add(formatState.getMetricValue(Metrics.NUM_ERRORS));
            nullErrCounter.add(formatState.getMetricValue(Metrics.NUM_NULL_ERRORS));
            duplicateErrCounter.add(formatState.getMetricValue(Metrics.NUM_DUPLICATE_ERRORS));
            conversionErrCounter.add(formatState.getMetricValue(Metrics.NUM_CONVERSION_ERRORS));
            otherErrCounter.add(formatState.getMetricValue(Metrics.NUM_OTHER_ERRORS));

            numWriteCounter.add(formatState.getMetricValue(Metrics.NUM_WRITES));

            snapshotWriteCounter.add(formatState.getMetricValue(Metrics.SNAPSHOT_WRITES));
            bytesWriteCounter.add(formatState.getMetricValue(Metrics.WRITE_BYTES));
            durationCounter.add(formatState.getMetricValue(Metrics.WRITE_DURATION));
        }
    }

    /** Turn on timed submission,Each result table is opened separately */
    private void initTimingSubmitTask() {
        if (batchSize > 1 && flushIntervalMills > 0) {
            log.info(
                    "initTimingSubmitTask() ,initialDelay:{}, delay:{}, MILLISECONDS",
                    flushIntervalMills,
                    flushIntervalMills);
            this.scheduler =
                    new ScheduledThreadPoolExecutor(
                            1, new ChunJunThreadFactory("timer-data-write-thread"));
            this.scheduledFuture =
                    this.scheduler.scheduleWithFixedDelay(
                            () -> {
                                synchronized (BaseRichOutputFormat.this) {
                                    if (closed) {
                                        return;
                                    }
                                    try {
                                        if (!rows.isEmpty()) {
                                            writeRecordInternal();
                                        }
                                    } catch (Exception e) {
                                        log.error(
                                                "Writing records failed. {}",
                                                ExceptionUtil.getErrorMessage(e));
                                        timerWriteException = e;
                                    }
                                }
                            },
                            flushIntervalMills,
                            flushIntervalMills,
                            TimeUnit.MILLISECONDS);
        }
    }

    /**
     * ??????????????????
     *
     * @param rowData ????????????
     */
    protected void writeSingleRecord(RowData rowData, LongCounter numWriteCounter) {
        try {
            writeSingleRecordInternal(rowData);
            numWriteCounter.add(1L);
        } catch (WriteRecordException e) {
            dirtyManager.collect(e.getRowData(), e, null);
            if (log.isTraceEnabled()) {
                log.trace(
                        "write error rowData, rowData = {}, e = {}",
                        rowData.toString(),
                        ExceptionUtil.getErrorMessage(e));
            }
        }
    }

    /** ?????????????????? */
    protected synchronized void writeRecordInternal() {
        if (flushEnable.get()) {
            try {
                writeMultipleRecordsInternal();
                numWriteCounter.add(rows.size());
            } catch (Exception e) {
                // ??????????????????????????????
                rows.forEach(item -> writeSingleRecord(item, numWriteCounter));
            } finally {
                // Data is either recorded dirty data or written normally
                rows.clear();
            }
        }
    }

    protected void checkTimerWriteException() {
        if (null != timerWriteException) {
            if (timerWriteException instanceof NoRestartException) {
                throw (NoRestartException) timerWriteException;
            } else if (timerWriteException instanceof RuntimeException) {
                throw (RuntimeException) timerWriteException;
            } else {
                throw new ChunJunRuntimeException("Writing records failed.", timerWriteException);
            }
        }
    }

    /**
     * ???????????????????????????
     *
     * @param pos ??????????????????
     * @param rowData ?????????????????????
     * @return ???????????????????????????
     */
    protected String recordConvertDetailErrorMessage(int pos, Object rowData) {
        return String.format(
                "%s WriteRecord error: when converting field[%s] in Row(%s)",
                getClass().getName(), pos, rowData);
    }

    /** ?????????????????????????????? */
    protected void updateDuration() {
        if (durationCounter != null) {
            durationCounter.resetLocal();
            durationCounter.add(System.currentTimeMillis() - startTime);
        }
    }

    /**
     * ??????checkpoint????????????map
     *
     * @return
     */
    public synchronized FormatState getFormatState() throws Exception {
        // not EXACTLY_ONCE model,Does not interact with the db
        if (Semantic.EXACTLY_ONCE == semantic) {
            try {
                log.info(
                        "getFormatState:Start preCommit, rowsOfCurrentTransaction: {}",
                        rowsOfCurrentTransaction);
                preCommit();
                checkTimerWriteException();
            } catch (Exception e) {
                log.error("preCommit error, e = {}", ExceptionUtil.getErrorMessage(e));
                if (e instanceof NoRestartException) {
                    throw e;
                }
            } finally {
                flushEnable.compareAndSet(true, false);
            }
        } else {
            writeRecordInternal();
        }
        // set metric after preCommit
        formatState.setNumberWrite(numWriteCounter.getLocalValue());
        formatState.setMetric(outputMetric.getMetricCounters());
        log.info("format state:{}", formatState.getState());
        return formatState;
    }

    private void executeDdlRowDataTemplate(DdlRowData ddlRowData) {
        try {
            preExecuteDdlRowData(ddlRowData);
            if (executeDdlAble) {
                executeDdlRowData(ddlRowData);
            }
        } catch (Exception e) {
            log.error("execute ddl {} error", ddlRowData);
            throw new RuntimeException(e);
        }
    }

    protected void preExecuteDdlRowData(DdlRowData rowData) throws Exception {}

    protected void executeDdlRowData(DdlRowData ddlRowData) throws Exception {
        throw new UnsupportedOperationException("not support execute ddlRowData");
    }

    /**
     * pre commit data
     *
     * @throws Exception
     */
    protected void preCommit() throws Exception {}

    /**
     * ??????????????????
     *
     * @param rowData ??????
     * @throws WriteRecordException
     */
    protected abstract void writeSingleRecordInternal(RowData rowData) throws WriteRecordException;

    /**
     * ??????????????????
     *
     * @throws Exception
     */
    protected abstract void writeMultipleRecordsInternal() throws Exception;

    /**
     * ???????????????????????????
     *
     * @param taskNumber ????????????
     * @param numTasks ????????????
     * @throws IOException
     */
    protected abstract void openInternal(int taskNumber, int numTasks) throws IOException;

    /**
     * ???????????????????????????
     *
     * @throws IOException
     */
    protected abstract void closeInternal() throws IOException;

    /**
     * checkpoint???????????????
     *
     * @param checkpointId
     */
    public synchronized void notifyCheckpointComplete(long checkpointId) {
        if (Semantic.EXACTLY_ONCE == semantic) {
            try {
                commit(checkpointId);
                log.info("notifyCheckpointComplete:Commit success , checkpointId:{}", checkpointId);
            } catch (Exception e) {
                log.error("commit error, e = {}", ExceptionUtil.getErrorMessage(e));
            } finally {
                flushEnable.compareAndSet(false, true);
            }
        }
    }

    /**
     * commit data
     *
     * @param checkpointId
     * @throws Exception
     */
    public void commit(long checkpointId) throws Exception {}

    /**
     * checkpoint???????????????
     *
     * @param checkpointId
     */
    public synchronized void notifyCheckpointAborted(long checkpointId) {
        if (Semantic.EXACTLY_ONCE == semantic) {
            try {
                rollback(checkpointId);
                log.info(
                        "notifyCheckpointAborted:rollback success , checkpointId:{}", checkpointId);
            } catch (Exception e) {
                log.error("rollback error, e = {}", ExceptionUtil.getErrorMessage(e));
            } finally {
                flushEnable.compareAndSet(false, true);
            }
        }
    }

    /**
     * rollback data
     *
     * @param checkpointId
     * @throws Exception
     */
    public void rollback(long checkpointId) throws Exception {}

    public void setRestoreState(FormatState formatState) {
        this.formatState = formatState;
    }

    public String getFormatId() {
        return formatId;
    }

    public void setFormatId(String formatId) {
        this.formatId = formatId;
    }

    public CommonConfig getConfig() {
        return config;
    }

    public void setConfig(CommonConfig config) {
        this.config = config;
    }

    public void setRowConverter(AbstractRowConverter rowConverter) {
        this.rowConverter = rowConverter;
    }

    public void setDirtyManager(DirtyManager dirtyManager) {
        this.dirtyManager = dirtyManager;
    }

    public void setExecuteDdlAble(boolean executeDdlAble) {
        this.executeDdlAble = executeDdlAble;
    }

    public void setUseAbstractColumn(boolean useAbstractColumn) {
        this.useAbstractColumn = useAbstractColumn;
    }

    public void setDdlConfig(DDLConfig ddlConfig) {
        this.ddlConfig = ddlConfig;
    }
}
