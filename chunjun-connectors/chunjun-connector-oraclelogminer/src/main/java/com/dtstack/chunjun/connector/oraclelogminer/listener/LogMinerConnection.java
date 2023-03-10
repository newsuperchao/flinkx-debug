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

package com.dtstack.chunjun.connector.oraclelogminer.listener;

import com.dtstack.chunjun.cdc.DdlRowDataBuilder;
import com.dtstack.chunjun.cdc.EventType;
import com.dtstack.chunjun.connector.oraclelogminer.config.LogMinerConfig;
import com.dtstack.chunjun.connector.oraclelogminer.entity.OracleInfo;
import com.dtstack.chunjun.connector.oraclelogminer.entity.QueueData;
import com.dtstack.chunjun.connector.oraclelogminer.entity.RecordLog;
import com.dtstack.chunjun.connector.oraclelogminer.util.SqlUtil;
import com.dtstack.chunjun.element.ColumnRowData;
import com.dtstack.chunjun.element.column.StringColumn;
import com.dtstack.chunjun.element.column.TimestampColumn;
import com.dtstack.chunjun.util.ClassUtil;
import com.dtstack.chunjun.util.ExceptionUtil;
import com.dtstack.chunjun.util.GsonUtil;
import com.dtstack.chunjun.util.RetryUtil;

import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
public class LogMinerConnection {
    public static final String KEY_PRIVILEGE = "PRIVILEGE";
    public static final String KEY_GRANTED_ROLE = "GRANTED_ROLE";
    public static final String CDB_CONTAINER_ROOT = "CDB$ROOT";
    public static final String DBA_ROLE = "DBA";
    public static final String LOG_TYPE_ARCHIVED = "ARCHIVED";
    public static final String EXECUTE_CATALOG_ROLE = "EXECUTE_CATALOG_ROLE";
    public static final int ORACLE_11_VERSION = 11;
    public static final List<String> PRIVILEGES_NEEDED =
            Arrays.asList(
                    "CREATE SESSION",
                    "LOGMINING",
                    "SELECT ANY TRANSACTION",
                    "SELECT ANY DICTIONARY");
    public static final List<String> ORACLE_11_PRIVILEGES_NEEDED =
            Arrays.asList("CREATE SESSION", "SELECT ANY TRANSACTION", "SELECT ANY DICTIONARY");
    public static final int RETRY_TIMES = 3;
    public static final int SLEEP_TIME = 2000;
    public static final String KEY_SEG_OWNER = "SEG_OWNER";
    public static final String KEY_TABLE_NAME = "TABLE_NAME";
    public static final String KEY_OPERATION = "OPERATION";
    public static final String KEY_OPERATION_CODE = "OPERATION_CODE";
    public static final String KEY_TIMESTAMP = "TIMESTAMP";
    public static final String KEY_SQL_REDO = "SQL_REDO";
    public static final String KEY_SQL_UNDO = "SQL_UNDO";
    public static final String KEY_CSF = "CSF";
    public static final String KEY_SCN = "SCN";
    public static final String KEY_CURRENT_SCN = "CURRENT_SCN";
    public static final String KEY_FIRST_CHANGE = "FIRST_CHANGE#";
    public static final String KEY_ROLLBACK = "ROLLBACK";
    public static final String KEY_ROW_ID = "ROW_ID";
    public static final String KEY_XID_USN = "XIDUSN";
    public static final String KEY_XID_SLT = "XIDSLT";
    public static final String KEY_XID_SQN = "XIDSQN";
    private static final long QUERY_LOG_INTERVAL = 10000;
    /** ?????????????????? * */
    private final Set<STATE> LOADING =
            Sets.newHashSet(
                    LogMinerConnection.STATE.FILEADDED,
                    LogMinerConnection.STATE.FILEADDING,
                    LogMinerConnection.STATE.LOADING);

    private final LogMinerConfig logMinerConfig;
    private final AtomicReference<STATE> CURRENT_STATE = new AtomicReference<>(STATE.INITIALIZE);
    private final TransactionManager transactionManager;
    /** oracle??????????????? * */
    public OracleInfo oracleInfo;
    /** ?????????logminer????????????????????? ?????????nextChange * */
    protected BigInteger startScn = null;
    /** ?????????logminer????????????????????? ?????????nextChange * */
    protected BigInteger endScn = null;

    private Connection connection;
    private CallableStatement logMinerStartStmt;
    private PreparedStatement logMinerSelectStmt;
    private ResultSet logMinerData;
    private QueueData result;
    private List<LogFile> addedLogFiles = new ArrayList<>();
    private long lastQueryTime;
    /** ???delete?????????rollback?????????????????????insert?????????connection */
    private LogMinerConnection queryDataForRollbackConnection;

    private Exception exception;

    public LogMinerConnection(
            LogMinerConfig logMinerConfig, TransactionManager transactionManager) {
        this.logMinerConfig = logMinerConfig;
        this.transactionManager = transactionManager;
    }

    /** ??????oracle????????? */
    public static OracleInfo getOracleInfo(Connection connection) throws SQLException {
        OracleInfo oracleInfo = new OracleInfo();

        oracleInfo.setVersion(connection.getMetaData().getDatabaseMajorVersion());

        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(SqlUtil.SQL_QUERY_ENCODING)) {
            rs.next();
            oracleInfo.setEncoding(rs.getString(1));
        }

        // ????????????19?????????????????????cdb??????
        if (oracleInfo.getVersion() == 19) {
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(SqlUtil.SQL_IS_CDB)) {
                rs.next();
                oracleInfo.setCdbMode(rs.getString(1).equalsIgnoreCase("YES"));
            }
        }

        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(SqlUtil.SQL_IS_RAC)) {
            rs.next();
            oracleInfo.setRacMode(rs.getString(1).equalsIgnoreCase("TRUE"));
        }

        log.info("oracle info {}", oracleInfo);
        return oracleInfo;
    }

    public void connect() {
        try {
            ClassUtil.forName(logMinerConfig.getDriverName(), getClass().getClassLoader());

            connection = getConnection();

            oracleInfo = getOracleInfo(connection);

            // ??????session????????? NLS_DATE_FORMAT ?????? "YYYY-MM-DD HH24:MI:SS"??????????????????????????? redolog??????
            // TO_DATE('18-APR-21', 'DD-MON-RR')

            try (PreparedStatement preparedStatement =
                    connection.prepareStatement(SqlUtil.SQL_ALTER_NLS_SESSION_PARAMETERS)) {
                preparedStatement.setQueryTimeout(logMinerConfig.getQueryTimeout().intValue());
                preparedStatement.execute();
            }

            // cdb???????????????CDB$ROOT???
            if (oracleInfo.isCdbMode()) {
                try (PreparedStatement preparedStatement =
                        connection.prepareStatement(
                                String.format(
                                        SqlUtil.SQL_ALTER_SESSION_CONTAINER, CDB_CONTAINER_ROOT))) {
                    preparedStatement.setQueryTimeout(logMinerConfig.getQueryTimeout().intValue());
                    preparedStatement.execute();
                }
            }

            log.info(
                    "get connection successfully, url:{}, username:{}, Oracle info???{}",
                    logMinerConfig.getJdbcUrl(),
                    logMinerConfig.getUsername(),
                    oracleInfo);
        } catch (Exception e) {
            String message =
                    String.format(
                            "get connection failed???url:[%s], username:[%s], e:%s",
                            logMinerConfig.getJdbcUrl(),
                            logMinerConfig.getUsername(),
                            ExceptionUtil.getErrorMessage(e));
            log.error(message);
            // ???????????? ????????????connection,??????connection ??? session???????????? ??????????????????
            closeResources(null, null, connection);
            throw new RuntimeException(message, e);
        }
    }

    /** ??????LogMiner?????? */
    public void disConnect() {
        this.CURRENT_STATE.set(STATE.INITIALIZE);
        // ??????????????????????????????LogMiner?????????????????????????????????
        addedLogFiles.clear();

        if (null != logMinerStartStmt) {
            try {
                logMinerStartStmt.execute(SqlUtil.SQL_STOP_LOG_MINER);
            } catch (SQLException e) {
                log.warn("close logMiner failed, e = {}", ExceptionUtil.getErrorMessage(e));
            }
        }

        closeStmt(logMinerStartStmt);
        closeResources(logMinerData, logMinerSelectStmt, connection);

        // queryDataForRollbackConnection ?????????????????????
        if (Objects.nonNull(queryDataForRollbackConnection)) {
            queryDataForRollbackConnection.disConnect();
        }
    }

    /** ??????LogMiner */
    public void startOrUpdateLogMiner(BigInteger startScn, BigInteger endScn) {

        String startSql;
        try {
            this.startScn = startScn;
            this.endScn = endScn;
            this.CURRENT_STATE.set(STATE.FILEADDING);

            checkAndResetConnection();

            // ?????????????????????????????????????????????????????????????????????????????????????????? QUERY_LOG_INTERVAL
            if (lastQueryTime > 0) {
                long time = System.currentTimeMillis() - lastQueryTime;
                if (time < QUERY_LOG_INTERVAL) {
                    try {
                        Thread.sleep(QUERY_LOG_INTERVAL - time);
                    } catch (InterruptedException e) {
                        log.warn("", e);
                    }
                }
            }
            lastQueryTime = System.currentTimeMillis();

            if (logMinerConfig.isSupportAutoAddLog()) {
                startSql =
                        oracleInfo.isOracle10()
                                ? SqlUtil.SQL_START_LOG_MINER_AUTO_ADD_LOG_10
                                : SqlUtil.SQL_START_LOG_MINER_AUTO_ADD_LOG;
            } else {
                startSql = SqlUtil.SQL_START_LOGMINER;
            }

            resetLogminerStmt(startSql);
            if (logMinerConfig.isSupportAutoAddLog()) {
                logMinerStartStmt.setString(1, startScn.toString());
            } else {
                logMinerStartStmt.setString(1, startScn.toString());
                logMinerStartStmt.setString(2, endScn.toString());
            }

            logMinerStartStmt.execute();
            this.CURRENT_STATE.set(STATE.FILEADDED);
            // ??????????????????logMiner??????????????????
            this.addedLogFiles = queryAddedLogFiles();
            log.info(
                    "Log group changed, startScn = {},endScn = {} new log group = {}",
                    startScn,
                    endScn,
                    GsonUtil.GSON.toJson(this.addedLogFiles));
        } catch (Exception e) {
            this.CURRENT_STATE.set(STATE.FAILED);
            this.exception = e;
            throw new RuntimeException(e);
        }
    }

    /** ???LogMiner?????????????????? */
    public boolean queryData(String logMinerSelectSql) {

        try {

            this.CURRENT_STATE.set(STATE.LOADING);
            checkAndResetConnection();

            closeStmt();
            logMinerSelectStmt =
                    connection.prepareStatement(
                            logMinerSelectSql,
                            ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY);
            configStatement(logMinerSelectStmt);

            logMinerSelectStmt.setFetchSize(logMinerConfig.getFetchSize());
            logMinerSelectStmt.setString(1, startScn.toString());
            logMinerSelectStmt.setString(2, endScn.toString());
            long before = System.currentTimeMillis();

            logMinerData = logMinerSelectStmt.executeQuery();

            this.CURRENT_STATE.set(STATE.READABLE);
            long timeConsuming = (System.currentTimeMillis() - before) / 1000;
            log.info(
                    "query LogMiner data, startScn:{},endScn:{},timeConsuming {}",
                    startScn,
                    endScn,
                    timeConsuming);
            return true;
        } catch (Exception e) {
            this.CURRENT_STATE.set(STATE.FAILED);
            this.exception = e;
            String message =
                    String.format(
                            "query logMiner data failed, sql:[%s], e: %s",
                            logMinerSelectSql, ExceptionUtil.getErrorMessage(e));
            throw new RuntimeException(message, e);
        }
    }

    /** ??????rollback????????? ???????????????dml?????? */
    public void queryDataForDeleteRollback(
            RecordLog recordLog,
            BigInteger startScn,
            BigInteger endScn,
            BigInteger earliestEndScn,
            String sql) {
        try {
            this.CURRENT_STATE.set(STATE.LOADING);
            closeStmt();
            logMinerSelectStmt =
                    connection.prepareStatement(
                            sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            configStatement(logMinerSelectStmt);

            logMinerSelectStmt.setFetchSize(logMinerConfig.getFetchSize());
            logMinerSelectStmt.setString(1, recordLog.getXidUsn());
            logMinerSelectStmt.setString(2, recordLog.getXidSlt());
            logMinerSelectStmt.setString(3, recordLog.getXidSqn());
            logMinerSelectStmt.setString(4, recordLog.getTableName());
            logMinerSelectStmt.setInt(5, 0);
            logMinerSelectStmt.setInt(6, 1);
            logMinerSelectStmt.setInt(7, 3);
            logMinerSelectStmt.setString(8, String.valueOf(startScn));
            logMinerSelectStmt.setString(9, String.valueOf(endScn));
            logMinerSelectStmt.setString(10, String.valueOf(earliestEndScn));

            long before = System.currentTimeMillis();
            logMinerData = logMinerSelectStmt.executeQuery();
            long timeConsuming = (System.currentTimeMillis() - before) / 1000;
            log.info(
                    "queryDataForDeleteRollback, startScn:{},endScn:{},timeConsuming {}",
                    startScn,
                    endScn,
                    timeConsuming);
            this.CURRENT_STATE.set(STATE.READABLE);
        } catch (SQLException e) {
            String message =
                    String.format(
                            "queryDataForRollback failed, sql:[%s], recordLog:[%s] e: %s",
                            sql, recordLog, ExceptionUtil.getErrorMessage(e));
            log.error(message);
            throw new RuntimeException(message, e);
        }
    }

    public BigInteger getStartScn(BigInteger startScn) {
        // restart from checkpoint
        if (null != startScn && startScn.compareTo(BigInteger.ZERO) != 0) {
            return startScn;
        }

        // ???????????????0?????????????????????????????????
        if (ReadPosition.ALL.name().equalsIgnoreCase(logMinerConfig.getReadPosition())) {
            // ??????????????????scn
            startScn = getMinScn();
        } else if (ReadPosition.CURRENT.name().equalsIgnoreCase(logMinerConfig.getReadPosition())) {
            startScn = getCurrentScn();
        } else if (ReadPosition.TIME.name().equalsIgnoreCase(logMinerConfig.getReadPosition())) {
            // ????????????????????????????????????????????????????????????????????????
            if (logMinerConfig.getStartTime() == 0) {
                throw new IllegalArgumentException(
                        "[startTime] must not be null or empty when readMode is [time]");
            }

            startScn = getLogFileStartPositionByTime(logMinerConfig.getStartTime());
        } else if (ReadPosition.SCN.name().equalsIgnoreCase(logMinerConfig.getReadPosition())) {
            // ???????????????scn???????????????????????????????????????
            if (StringUtils.isEmpty(logMinerConfig.getStartScn())) {
                throw new IllegalArgumentException(
                        "[startSCN] must not be null or empty when readMode is [scn]");
            }

            startScn = new BigInteger(logMinerConfig.getStartScn());
        } else {
            throw new IllegalArgumentException(
                    "unsupported readMode : " + logMinerConfig.getReadPosition());
        }

        return startScn;
    }

    private BigInteger getMinScn() {
        BigInteger minScn = null;
        PreparedStatement minScnStmt = null;
        ResultSet minScnResultSet = null;

        try {
            minScnStmt = connection.prepareCall(SqlUtil.SQL_GET_LOG_FILE_START_POSITION);
            configStatement(minScnStmt);

            minScnResultSet = minScnStmt.executeQuery();
            while (minScnResultSet.next()) {
                minScn = new BigInteger(minScnResultSet.getString(KEY_FIRST_CHANGE));
            }

            return minScn;
        } catch (SQLException e) {
            log.error(" obtaining the starting position of the earliest archive log error", e);
            throw new RuntimeException(e);
        } finally {
            closeResources(minScnResultSet, minScnStmt, null);
        }
    }

    protected BigInteger getCurrentScn() {
        BigInteger currentScn = null;
        CallableStatement currentScnStmt = null;
        ResultSet currentScnResultSet = null;

        try {
            currentScnStmt = connection.prepareCall(SqlUtil.SQL_GET_CURRENT_SCN);
            configStatement(currentScnStmt);

            currentScnResultSet = currentScnStmt.executeQuery();
            while (currentScnResultSet.next()) {
                currentScn = new BigInteger(currentScnResultSet.getString(KEY_CURRENT_SCN));
            }

            return currentScn;
        } catch (SQLException e) {
            log.error("???????????????SCN??????:", e);
            throw new RuntimeException(e);
        } finally {
            closeResources(currentScnResultSet, currentScnStmt, null);
        }
    }

    private BigInteger getLogFileStartPositionByTime(Long time) {
        BigInteger logFileFirstChange = null;

        PreparedStatement lastLogFileStmt = null;
        ResultSet lastLogFileResultSet = null;

        try {
            String timeStr = DateFormatUtils.format(time, "yyyy-MM-dd HH:mm:ss");

            lastLogFileStmt =
                    connection.prepareCall(
                            oracleInfo.isOracle10()
                                    ? SqlUtil.SQL_GET_LOG_FILE_START_POSITION_BY_TIME_10
                                    : SqlUtil.SQL_GET_LOG_FILE_START_POSITION_BY_TIME);
            configStatement(lastLogFileStmt);

            lastLogFileStmt.setString(1, timeStr);
            lastLogFileStmt.setString(2, timeStr);

            if (!oracleInfo.isOracle10()) {
                // oracle10??????????????????
                lastLogFileStmt.setString(3, timeStr);
            }
            lastLogFileResultSet = lastLogFileStmt.executeQuery();
            while (lastLogFileResultSet.next()) {
                logFileFirstChange =
                        new BigInteger(lastLogFileResultSet.getString(KEY_FIRST_CHANGE));
            }

            return logFileFirstChange;
        } catch (SQLException e) {
            log.error("????????????:[{}]??????????????????????????????????????????", time, e);
            throw new RuntimeException(e);
        } finally {
            closeResources(lastLogFileResultSet, lastLogFileStmt, null);
        }
    }

    /** ??????????????????????????? */
    private void closeResources(ResultSet rs, Statement stmt, Connection conn) {
        if (null != rs) {
            try {
                rs.close();
            } catch (SQLException e) {
                log.warn("Close resultSet error: {}", ExceptionUtil.getErrorMessage(e));
            }
        }

        closeStmt(stmt);

        if (null != conn) {
            try {
                conn.close();
            } catch (SQLException e) {
                log.warn("Close connection error:{}", ExceptionUtil.getErrorMessage(e));
            }
        }
    }

    /** ??????leftScn ????????????????????????????????? ??????????????????scn?????? ???????????????????????????????????? */
    protected Pair<BigInteger, Boolean> getEndScn(BigInteger startScn, List<LogFile> logFiles)
            throws SQLException {
        return getEndScn(startScn, logFiles, true);
    }

    protected Pair<BigInteger, Boolean> getEndScn(
            BigInteger startScn, List<LogFile> logFiles, boolean addRedoLog) throws SQLException {

        List<LogFile> logFileLists = new ArrayList<>();
        PreparedStatement statement = null;
        ResultSet rs = null;
        try {
            checkAndResetConnection();
            String sql;
            if (addRedoLog) {
                sql =
                        oracleInfo.isOracle10()
                                ? SqlUtil.SQL_QUERY_LOG_FILE_10
                                : SqlUtil.SQL_QUERY_LOG_FILE;
            } else {
                sql =
                        oracleInfo.isOracle10()
                                ? SqlUtil.SQL_QUERY_ARCHIVE_LOG_FILE_10
                                : SqlUtil.SQL_QUERY_ARCHIVE_LOG_FILE;
            }
            statement = connection.prepareStatement(sql);
            statement.setString(1, startScn.toString());
            statement.setString(2, startScn.toString());
            rs = statement.executeQuery();
            while (rs.next()) {
                LogFile logFile = new LogFile();
                logFile.setFileName(rs.getString("name"));
                logFile.setFirstChange(new BigInteger(rs.getString("first_change#")));
                logFile.setNextChange(new BigInteger(rs.getString("next_change#")));
                logFile.setThread(rs.getLong("thread#"));
                logFile.setBytes(rs.getLong("BYTES"));
                logFile.setType(rs.getString("TYPE"));
                logFileLists.add(logFile);
            }
        } finally {
            closeResources(rs, statement, null);
        }

        Map<Long, List<LogFile>> map =
                logFileLists.stream().collect(Collectors.groupingBy(LogFile::getThread));

        // ????????????thread?????????????????????
        map.forEach(
                (k, v) ->
                        map.put(
                                k,
                                v.stream()
                                        .sorted(Comparator.comparing(LogFile::getFirstChange))
                                        .collect(Collectors.toList())));

        BigInteger endScn = startScn;
        boolean loadRedoLog = false;

        long fileSize = 0L;
        Collection<List<LogFile>> values = map.values();

        while (fileSize < logMinerConfig.getMaxLogFileSize()) {
            List<LogFile> tempList = new ArrayList<>(8);
            for (List<LogFile> logFileList : values) {
                for (LogFile logFile1 : logFileList) {
                    if (!logFiles.contains(logFile1)) {
                        // ??????thread?????????????????????????????????
                        tempList.add(logFile1);
                        break;
                    }
                }
            }
            // ???????????? ??????????????????????????????????????? ????????????
            if (CollectionUtils.isEmpty(tempList)) {
                break;
            }
            // ???????????????nextSCN
            BigInteger minNextScn =
                    tempList.stream()
                            .sorted(Comparator.comparing(LogFile::getNextChange))
                            .collect(Collectors.toList())
                            .get(0)
                            .getNextChange();

            for (LogFile logFile1 : tempList) {
                if (logFile1.getFirstChange().compareTo(minNextScn) < 0) {
                    logFiles.add(logFile1);
                    fileSize += logFile1.getBytes();
                    if (logFile1.isOnline()) {
                        loadRedoLog = true;
                    }
                }
            }
            endScn = minNextScn;
        }

        if (loadRedoLog) {
            // ??????logminer?????????????????????????????????online???????????????????????????rightScn????????????SCN
            endScn = getCurrentScn();
            logFiles = logFileLists;
        }

        if (CollectionUtils.isEmpty(logFiles)) {
            return Pair.of(null, loadRedoLog);
        }

        log.info(
                "getEndScn success,startScn:{},endScn:{}, addRedoLog:{}, loadRedoLog:{}",
                startScn,
                endScn,
                addRedoLog,
                loadRedoLog);
        return Pair.of(endScn, loadRedoLog);
    }

    /** ??????logminer????????????????????? */
    private List<LogFile> queryAddedLogFiles() throws SQLException {
        List<LogFile> logFileLists = new ArrayList<>();
        try (PreparedStatement statement =
                connection.prepareStatement(SqlUtil.SQL_QUERY_ADDED_LOG)) {
            statement.setQueryTimeout(logMinerConfig.getQueryTimeout().intValue());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LogFile logFile = new LogFile();
                    logFile.setFileName(rs.getString("filename"));
                    logFile.setFirstChange(new BigInteger(rs.getString("low_scn")));
                    logFile.setNextChange(new BigInteger(rs.getString("next_scn")));
                    logFile.setThread(rs.getLong("thread_id"));
                    logFile.setBytes(rs.getLong("filesize"));
                    logFile.setStatus(rs.getInt("status"));
                    logFile.setType(rs.getString("type"));
                    logFileLists.add(logFile);
                }
            }
        }
        return logFileLists;
    }

    public boolean hasNext() throws SQLException, UnsupportedEncodingException, DecoderException {
        return hasNext(null, null);
    }

    public boolean hasNext(BigInteger endScn, String endRowid)
            throws SQLException, UnsupportedEncodingException, DecoderException {
        if (null == logMinerData
                || logMinerData.isClosed()
                || this.CURRENT_STATE.get().equals(STATE.READEND)) {
            this.CURRENT_STATE.set(STATE.READEND);
            return false;
        }

        String sqlLog;
        while (logMinerData.next()) {
            String sql = logMinerData.getString(KEY_SQL_REDO);
            if (StringUtils.isBlank(sql)) {
                continue;
            }
            StringBuilder sqlRedo = new StringBuilder(sql);
            StringBuilder sqlUndo =
                    new StringBuilder(
                            Objects.nonNull(logMinerData.getString(KEY_SQL_UNDO))
                                    ? logMinerData.getString(KEY_SQL_UNDO)
                                    : "");
            if (SqlUtil.isCreateTemporaryTableSql(sqlRedo.toString())) {
                continue;
            }
            BigInteger scn = new BigInteger(logMinerData.getString(KEY_SCN));
            String operation = logMinerData.getString(KEY_OPERATION);
            int operationCode = logMinerData.getInt(KEY_OPERATION_CODE);
            String tableName = logMinerData.getString(KEY_TABLE_NAME);

            boolean hasMultiSql;

            String xidSqn = logMinerData.getString(KEY_XID_SQN);
            String xidUsn = logMinerData.getString(KEY_XID_USN);
            String xidSLt = logMinerData.getString(KEY_XID_SLT);
            String rowId = logMinerData.getString(KEY_ROW_ID);
            boolean rollback = logMinerData.getBoolean(KEY_ROLLBACK);

            // ???????????????commit / rollback???????????????
            // refer to
            // https://docs.oracle.com/cd/B19306_01/server.102/b14237/dynviews_1154.htm#REFRN30132
            if (operationCode == 7 || operationCode == 36) {
                transactionManager.cleanCache(xidUsn, xidSLt, xidSqn);
                continue;
            }

            if (endScn != null && rowId != null) {
                if (scn.compareTo(endScn) > 0) {
                    return false;
                }
                if (scn.compareTo(endScn) == 0 && rowId.equals(endRowid)) {
                    return false;
                }
            }
            // ???CSF???????????????sql??????????????????????????????sql??????4000 ???????????????????????????
            boolean isSqlNotEnd = logMinerData.getBoolean(KEY_CSF);
            // ??????????????????SQL
            hasMultiSql = isSqlNotEnd;

            while (isSqlNotEnd) {
                logMinerData.next();
                // redoLog ??????????????????????????????  ??????sqlUndo????????????????????????redolog??????null
                String sqlRedoValue = logMinerData.getString(KEY_SQL_REDO);
                if (Objects.nonNull(sqlRedoValue)) {
                    sqlRedo.append(sqlRedoValue);
                }

                String sqlUndoValue = logMinerData.getString(KEY_SQL_UNDO);
                if (Objects.nonNull(sqlUndoValue)) {
                    sqlUndo.append(sqlUndoValue);
                }
                isSqlNotEnd = logMinerData.getBoolean(KEY_CSF);
            }

            if (operationCode == 5) {
                result =
                        new QueueData(
                                scn,
                                DdlRowDataBuilder.builder()
                                        .setDatabaseName(null)
                                        .setSchemaName(logMinerData.getString(KEY_SEG_OWNER))
                                        .setTableName(tableName)
                                        .setContent(sqlRedo.toString())
                                        .setType(EventType.UNKNOWN.name())
                                        .setLsn(String.valueOf(scn))
                                        .setLsnSequence("0")
                                        .build());
                return true;
            }

            // delete from "table"."ID" where ROWID = 'AAADcjAAFAAAABoAAC' delete????????????rowid??????????????????
            // update "schema"."table" set "ID" = '29' ??????where??????
            if (rollback && (operationCode == 2 || operationCode == 3)) {
                StringBuilder undoLog = new StringBuilder(1024);

                // ??????????????????rollback?????????DML??????
                RecordLog recordLog =
                        transactionManager.queryUndoLogFromCache(xidUsn, xidSLt, xidSqn);

                if (Objects.isNull(recordLog)) {

                    Pair<BigInteger, String> earliestRollbackOperation =
                            transactionManager.getEarliestRollbackOperation(xidUsn, xidSLt, xidSqn);
                    BigInteger earliestScn = scn;
                    String earliestRowid = rowId;
                    if (earliestRollbackOperation != null) {
                        earliestScn = earliestRollbackOperation.getLeft();
                        earliestRowid = earliestRollbackOperation.getRight();
                    }

                    // ??????DML?????????????????? ?????? ???rollback?????????????????????????????? ?????????????????????????????????
                    recordLog =
                            recursionQueryDataForRollback(
                                    new RecordLog(
                                            scn,
                                            "",
                                            "",
                                            xidUsn,
                                            xidSLt,
                                            xidSqn,
                                            rowId,
                                            logMinerData.getString(KEY_TABLE_NAME),
                                            false,
                                            operationCode),
                                    earliestScn,
                                    earliestRowid);
                }

                if (Objects.nonNull(recordLog)) {
                    RecordLog rollbackLog =
                            new RecordLog(
                                    scn,
                                    sqlUndo.toString(),
                                    sqlRedo.toString(),
                                    xidUsn,
                                    xidSLt,
                                    xidSqn,
                                    rowId,
                                    tableName,
                                    hasMultiSql,
                                    operationCode);
                    String rollbackSql = getRollbackSql(rollbackLog, recordLog);
                    undoLog.append(rollbackSql);
                    hasMultiSql = recordLog.isHasMultiSql();
                }

                if (undoLog.length() == 0) {
                    // ????????????????????????insert?????? ??????delete where rowid=xxx ????????????redoLog
                    log.warn("has not found undoLog for scn {}", scn);
                } else {
                    sqlRedo = undoLog;
                }
                log.debug(
                        "find rollback sql,scn is {},rowId is {},xisSqn is {}", scn, rowId, xidSqn);
            }

            // oracle10??????????????????????????????4000???LogMiner??????????????????????????????SQL????????????
            if (hasMultiSql && oracleInfo.isOracle10() && oracleInfo.isGbk()) {
                String redo = sqlRedo.toString();
                String hexStr = new String(Hex.encodeHex(redo.getBytes("GBK")));
                boolean hasChange = false;
                if (operationCode == 1 && hexStr.contains("3f2c")) {
                    log.info(
                            "current scn is: {},\noriginal redo sql is: {},\nhex redo string is: {}",
                            scn,
                            redo,
                            hexStr);
                    hasChange = true;
                    hexStr = hexStr.replace("3f2c", "272c");
                }
                if (operationCode != 1) {
                    if (hexStr.contains("3f20616e64")) {
                        log.info(
                                "current scn is: {},\noriginal redo sql is: {},\nhex redo string is: {}",
                                scn,
                                redo,
                                hexStr);
                        hasChange = true;
                        // update set "" = '' and "" = '' where "" = '' and "" = '' where???????????????????????????
                        // delete from where "" = '' and "" = '' where???????????????????????????
                        // ???????and -> '??????and
                        hexStr = hexStr.replace("3f20616e64", "2720616e64");
                    }

                    if (hexStr.contains("3f207768657265")) {
                        log.info(
                                "current scn is: {},\noriginal redo sql is: {},\nhex redo string is: {}",
                                scn,
                                redo,
                                hexStr);
                        hasChange = true;
                        // ? where ?????? ' where
                        hexStr = hexStr.replace("3f207768657265", "27207768657265");
                    }
                }

                if (hasChange) {
                    sqlLog = new String(Hex.decodeHex(hexStr.toCharArray()), "GBK");
                    log.info("final redo sql is: {}", sqlLog);
                } else {
                    sqlLog = sqlRedo.toString();
                }
            } else {
                sqlLog = sqlRedo.toString();
            }

            String schema = logMinerData.getString(KEY_SEG_OWNER);
            Timestamp timestamp = logMinerData.getTimestamp(KEY_TIMESTAMP);

            ColumnRowData columnRowData = new ColumnRowData(5);
            columnRowData.addField(new StringColumn(schema));
            columnRowData.addHeader("schema");

            columnRowData.addField(new StringColumn(tableName));
            columnRowData.addHeader("tableName");

            columnRowData.addField(new StringColumn(operation));
            columnRowData.addHeader("operation");

            columnRowData.addField(new StringColumn(sqlLog));
            columnRowData.addHeader("sqlLog");

            columnRowData.addField(new TimestampColumn(timestamp));
            columnRowData.addHeader("opTime");

            result = new QueueData(scn, columnRowData);

            // ??????????????????insert update???????????????????????????
            if (!rollback) {
                transactionManager.putCache(
                        new RecordLog(
                                scn,
                                sqlUndo.toString(),
                                sqlLog,
                                xidUsn,
                                xidSLt,
                                xidSqn,
                                rowId,
                                tableName,
                                hasMultiSql,
                                operationCode));
            }
            return true;
        }

        this.CURRENT_STATE.set(STATE.READEND);
        return false;
    }

    // ????????????????????????
    public boolean isValid() {
        try {
            return connection != null
                    && connection.isValid(logMinerConfig.getQueryTimeout().intValue());
        } catch (SQLException e) {
            return false;
        }
    }

    public void checkPrivileges() {
        try (Statement statement = connection.createStatement()) {

            List<String> roles = getUserRoles(statement);
            if (roles.contains(DBA_ROLE)) {
                return;
            }

            if (!roles.contains(EXECUTE_CATALOG_ROLE)) {
                throw new IllegalArgumentException(
                        "users in non DBA roles must be [EXECUTE_CATALOG_ROLE] Role, please execute SQL GRANT???GRANT EXECUTE_CATALOG_ROLE TO USERNAME");
            }

            if (containsNeededPrivileges(statement)) {
                return;
            }

            String message;
            if (oracleInfo.getVersion() <= ORACLE_11_VERSION) {
                message =
                        "Insufficient permissions, please execute sql authorization???GRANT CREATE SESSION, EXECUTE_CATALOG_ROLE, SELECT ANY TRANSACTION, FLASHBACK ANY TABLE, SELECT ANY TABLE, LOCK ANY TABLE, SELECT ANY DICTIONARY TO USER_ROLE;";
            } else {
                message =
                        "Insufficient permissions, please execute sql authorization???GRANT LOGMINING, CREATE SESSION, SELECT ANY TRANSACTION ,SELECT ANY DICTIONARY TO USER_ROLE;";
            }

            throw new IllegalArgumentException(message);
        } catch (SQLException e) {
            throw new RuntimeException("check permissions failed", e);
        }
    }

    private boolean containsNeededPrivileges(Statement statement) {
        try (ResultSet rs = statement.executeQuery(SqlUtil.SQL_QUERY_PRIVILEGES)) {
            List<String> privileges = new ArrayList<>();
            while (rs.next()) {
                String privilege = rs.getString(KEY_PRIVILEGE);
                if (StringUtils.isNotEmpty(privilege)) {
                    privileges.add(privilege.toUpperCase());
                }
            }

            int privilegeCount = 0;
            List<String> privilegeList;
            if (oracleInfo.getVersion() <= ORACLE_11_VERSION) {
                privilegeList = ORACLE_11_PRIVILEGES_NEEDED;
            } else {
                privilegeList = PRIVILEGES_NEEDED;
            }
            for (String privilege : privilegeList) {
                if (privileges.contains(privilege)) {
                    privilegeCount++;
                }
            }

            return privilegeCount == privilegeList.size();
        } catch (SQLException e) {
            throw new RuntimeException("check user permissions error", e);
        }
    }

    private List<String> getUserRoles(Statement statement) {
        try (ResultSet rs = statement.executeQuery(SqlUtil.SQL_QUERY_ROLES)) {
            List<String> roles = new ArrayList<>();
            while (rs.next()) {
                String role = rs.getString(KEY_GRANTED_ROLE);
                if (StringUtils.isNotEmpty(role)) {
                    roles.add(role.toUpperCase());
                }
            }

            return roles;
        } catch (SQLException e) {
            throw new RuntimeException("check user permissions error", e);
        }
    }

    private void configStatement(java.sql.Statement statement) throws SQLException {
        if (logMinerConfig.getQueryTimeout() != null) {
            statement.setQueryTimeout(logMinerConfig.getQueryTimeout().intValue());
        }
    }

    public QueueData next() {
        return result;
    }

    /** ??????logMinerSelectStmt */
    public void closeStmt() {
        try {
            if (logMinerSelectStmt != null && !logMinerSelectStmt.isClosed()) {
                logMinerSelectStmt.close();
            }
        } catch (SQLException e) {
            log.warn("Close logMinerSelectStmt error", e);
        }
        logMinerSelectStmt = null;
    }

    /** ??????Statement */
    private void closeStmt(Statement statement) {
        try {
            if (statement != null && !statement.isClosed()) {
                statement.close();
            }
        } catch (SQLException e) {
            log.warn("Close statement error", e);
        }
    }

    /**
     * ???????????? delete???rollback?????????insert??????
     *
     * @param rollbackRecord rollback??????
     * @return insert??????
     */
    public RecordLog recursionQueryDataForRollback(
            RecordLog rollbackRecord, BigInteger earliestEndScn, String earliestRowid)
            throws SQLException, UnsupportedEncodingException, DecoderException {
        if (Objects.isNull(queryDataForRollbackConnection)) {
            queryDataForRollbackConnection =
                    new LogMinerConnection(logMinerConfig, transactionManager);
        }

        if (Objects.isNull(queryDataForRollbackConnection.connection)
                || queryDataForRollbackConnection.connection.isClosed()) {
            log.info("queryDataForRollbackConnection start connect");
            queryDataForRollbackConnection.connect();
        }

        BigInteger endScn = earliestEndScn;
        BigInteger minScn = getMinScn();
        for (int i = 0; ; i++) {
            BigInteger startScn =
                    endScn.subtract(new BigInteger("5000"))
                            .subtract(new BigInteger((2000 * i) + ""));
            if (startScn.compareTo(minScn) <= 0) {
                startScn = minScn;
            }
            log.info(
                    "queryDataForRollbackConnection startScn{}, endScn{}, earliestEndScn:{}, rowid:{},table:{}",
                    startScn,
                    endScn,
                    earliestEndScn,
                    earliestRowid,
                    rollbackRecord.getTableName());
            queryDataForRollbackConnection.startOrUpdateLogMiner(
                    startScn, endScn.add(BigInteger.ONE));
            queryDataForRollbackConnection.queryDataForDeleteRollback(
                    rollbackRecord, startScn, endScn, earliestEndScn, SqlUtil.queryDataForRollback);
            // while???????????????????????? ???????????????????????????
            while (queryDataForRollbackConnection.hasNext(earliestEndScn, earliestRowid)) {}
            // ???????????????
            RecordLog dmlLog =
                    transactionManager.queryUndoLogFromCache(
                            rollbackRecord.getXidUsn(),
                            rollbackRecord.getXidSlt(),
                            rollbackRecord.getXidSqn());
            if (Objects.nonNull(dmlLog)) {
                return dmlLog;
            }
            endScn = startScn;
            if (startScn.compareTo(minScn) <= 0) {
                log.warn(
                        "select all file but not found log for rollback data, xidUsn {},xidSlt {},xidSqn {},scn {}",
                        rollbackRecord.getXidUsn(),
                        rollbackRecord.getXidSlt(),
                        rollbackRecord.getXidSqn(),
                        rollbackRecord.getScn());
                break;
            }
        }

        return null;
    }

    /** ?????? ??????logminer???statement */
    public void resetLogminerStmt(String startSql) throws SQLException {
        closeStmt(logMinerStartStmt);
        logMinerStartStmt = connection.prepareCall(startSql);
        configStatement(logMinerStartStmt);
    }

    public void checkAndResetConnection() {
        if (!isValid()) {
            connect();
        }
    }

    /**
     * ???????????????????????????dml?????????????????????undoog
     *
     * @param rollbackLog ????????????
     * @param dmlLog ?????????dml??????
     */
    public String getRollbackSql(RecordLog rollbackLog, RecordLog dmlLog) {
        // ?????????????????????update?????????where???????????? ????????????
        if (rollbackLog.getOperationCode() == 3 && dmlLog.getOperationCode() == 3) {
            return dmlLog.getSqlUndo();
        }

        // ???????????????delete
        // delete?????????????????? ???????????????????????????blob??????????????????blob???????????? ???????????????insert emptyBlob??????????????????update????????????
        // ?????????????????????delete???????????????delete??????rowid???????????????update?????? ??????????????????delete from "table"."ID" where ROWID =
        // 'AAADcjAAFAAAABoAAC'???blob????????????
        if (rollbackLog.getOperationCode() == 2 && dmlLog.getOperationCode() == 1) {
            return dmlLog.getSqlUndo();
        }
        log.warn("dmlLog [{}]  is not hit for rollbackLog [{}]", dmlLog, rollbackLog);
        return "";
    }

    /** ???????????????????????? * */
    public boolean isLoading() {
        return LOADING.contains(this.CURRENT_STATE.get());
    }

    @Override
    public String toString() {
        return "LogMinerConnection{"
                + "startScn="
                + startScn
                + ",endScn="
                + endScn
                + ",currentState="
                + CURRENT_STATE
                + '}';
    }

    public STATE getState() {
        return this.CURRENT_STATE.get();
    }

    public enum ReadPosition {
        ALL,
        CURRENT,
        TIME,
        SCN
    }

    public enum STATE {
        INITIALIZE,
        FILEADDING,
        FILEADDED,
        LOADING,
        READABLE,
        READEND,
        FAILED
    }

    protected Connection getConnection() {
        java.util.Properties info = new java.util.Properties();
        if (logMinerConfig.getUsername() != null) {
            info.put("user", logMinerConfig.getUsername());
        }
        if (logMinerConfig.getPassword() != null) {
            info.put("password", logMinerConfig.getPassword());
        }

        // queryTimeOut ???????????? ??????????????????
        info.put("oracle.jdbc.ReadTimeout", (logMinerConfig.getQueryTimeout() + 60) * 1000 + "");

        if (Objects.nonNull(logMinerConfig.getProperties())) {
            info.putAll(logMinerConfig.getProperties());
        }
        Properties printProperties = new Properties();
        printProperties.putAll(info);
        printProperties.put("password", "******");
        log.info("connection properties is {}", printProperties);
        return RetryUtil.executeWithRetry(
                () -> DriverManager.getConnection(logMinerConfig.getJdbcUrl(), info),
                RETRY_TIMES,
                SLEEP_TIME,
                false);
    }

    public Exception getE() {
        return exception;
    }
}
