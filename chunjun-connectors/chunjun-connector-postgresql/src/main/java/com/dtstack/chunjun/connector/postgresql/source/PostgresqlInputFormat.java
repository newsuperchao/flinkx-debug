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

package com.dtstack.chunjun.connector.postgresql.source;

import com.dtstack.chunjun.connector.jdbc.source.JdbcInputFormat;
import com.dtstack.chunjun.connector.jdbc.util.SqlUtil;
import com.dtstack.chunjun.util.ExceptionUtil;

import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class PostgresqlInputFormat extends JdbcInputFormat {

    private static final long serialVersionUID = -2996563902455817663L;

    @Override
    protected void queryPollingWithOutStartLocation() throws SQLException {
        // In PostgreSQL, if resultCursorType is FORWARD_ONLY
        // , the query will report an error after the method
        // #setFetchDirection(ResultSet.FETCH_REVERSE) is called.
        String querySql =
                jdbcConfig.getQuerySql() + SqlUtil.buildOrderSql(jdbcConfig, jdbcDialect, "ASC");
        ps =
                dbConn.prepareStatement(
                        querySql, ResultSet.TYPE_SCROLL_INSENSITIVE, resultSetConcurrency);
        ps.setFetchSize(jdbcConfig.getFetchSize());
        ps.setQueryTimeout(jdbcConfig.getQueryTimeOut());
        resultSet = ps.executeQuery();
        hasNext = resultSet.next();

        try {
            // ?????????????????????????????????????????????????????????????????????
            while (!hasNext) {
                TimeUnit.MILLISECONDS.sleep(jdbcConfig.getPollingInterval());
                resultSet.close();
                // ????????????????????? ??????????????????????????????????????? ?????????????????????
                dbConn.commit();
                resultSet = ps.executeQuery();
                hasNext = resultSet.next();
                // ??????????????????????????????(???????????? - ??????????????????) % 300??? <= ????????????????????????
                if ((System.currentTimeMillis() - startTime) % 300000
                        <= jdbcConfig.getPollingInterval()) {
                    log.info(
                            "no record matched condition in database, execute query sql = {}, startLocation = {}",
                            jdbcConfig.getQuerySql(),
                            endLocationAccumulator.getLocalValue());
                }
            }
        } catch (InterruptedException e) {
            log.warn(
                    "interrupted while waiting for polling, e = {}",
                    ExceptionUtil.getErrorMessage(e));
        }

        // ????????????????????????querySql
        StringBuilder builder = new StringBuilder(128);
        builder.append(jdbcConfig.getQuerySql());
        if (jdbcConfig.getQuerySql().contains("WHERE")) {
            builder.append(" AND ");
        } else {
            builder.append(" WHERE ");
        }
        builder.append(jdbcDialect.quoteIdentifier(jdbcConfig.getIncreColumn()))
                .append(" > ? ORDER BY ")
                .append(jdbcDialect.quoteIdentifier(jdbcConfig.getIncreColumn()))
                .append(" ASC");
        jdbcConfig.setQuerySql(builder.toString());
        ps =
                dbConn.prepareStatement(
                        jdbcConfig.getQuerySql(),
                        ResultSet.TYPE_SCROLL_INSENSITIVE,
                        resultSetConcurrency);
        ps.setFetchSize(jdbcConfig.getFetchSize());
        ps.setQueryTimeout(jdbcConfig.getQueryTimeOut());
        log.info("update querySql, sql = {}", jdbcConfig.getQuerySql());
    }
}
