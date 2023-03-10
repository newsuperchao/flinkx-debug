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

package com.dtstack.chunjun.lookup;

import com.dtstack.chunjun.converter.AbstractRowConverter;
import com.dtstack.chunjun.factory.ChunJunThreadFactory;
import com.dtstack.chunjun.lookup.config.LookupConfig;

import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.LookupFunction;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.types.RowKind;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
public abstract class AbstractAllTableFunction extends LookupFunction {

    private static final long serialVersionUID = 5565390751716048922L;
    /** ?????????join??????????????? */
    protected final String[] keyNames;
    /** ?????? */
    protected AtomicReference<Object> cacheRef = new AtomicReference<>();
    /** ???????????? */
    private ScheduledExecutorService es;
    /** ???????????? */
    protected final LookupConfig lookupConfig;
    /** ???????????? */
    protected final String[] fieldsName;
    /** ????????????????????? */
    protected final AbstractRowConverter rowConverter;

    protected final RowData.FieldGetter[] fieldGetters;

    public AbstractAllTableFunction(
            String[] fieldNames,
            String[] keyNames,
            LookupConfig lookupConfig,
            AbstractRowConverter rowConverter) {
        this.keyNames = keyNames;
        this.lookupConfig = lookupConfig;
        this.fieldsName = fieldNames;
        this.rowConverter = rowConverter;
        this.fieldGetters = new RowData.FieldGetter[keyNames.length];
        List<Pair<LogicalType, Integer>> fieldTypeAndPositionOfKeyField =
                getFieldTypeAndPositionOfKeyField(keyNames, rowConverter.getRowType());
        for (int i = 0; i < fieldTypeAndPositionOfKeyField.size(); i++) {
            Pair<LogicalType, Integer> typeAndPosition = fieldTypeAndPositionOfKeyField.get(i);
            fieldGetters[i] =
                    RowData.createFieldGetter(
                            typeAndPosition.getLeft(), typeAndPosition.getRight());
        }
    }

    protected List<Pair<LogicalType, Integer>> getFieldTypeAndPositionOfKeyField(
            String[] keyNames, RowType rowType) {
        List<Pair<LogicalType, Integer>> typeAndPosition = Lists.newLinkedList();
        for (int i = 0; i < keyNames.length; i++) {
            LogicalType type = rowType.getTypeAt(rowType.getFieldIndex(keyNames[i]));
            typeAndPosition.add(Pair.of(type, i));
        }
        return typeAndPosition;
    }

    /** ????????????????????????????????? */
    protected void initCache() {
        Map<String, List<Map<String, Object>>> newCache = Maps.newConcurrentMap();
        cacheRef.set(newCache);
        loadData(newCache);
    }

    /** ?????????????????????????????? */
    protected void reloadCache() {
        // reload cacheRef and replace to old cacheRef
        Map<String, List<Map<String, Object>>> newCache = Maps.newConcurrentMap();
        try {
            loadData(newCache);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        cacheRef.set(newCache);
        log.info(
                "----- " + lookupConfig.getTableName() + ": all cacheRef reload end:{}",
                LocalDateTime.now());
    }

    /**
     * ?????????????????????
     *
     * @param cacheRef
     */
    protected abstract void loadData(Object cacheRef);

    @Override
    public void open(FunctionContext context) throws Exception {
        super.open(context);
        initCache();
        log.info("----- all cacheRef init end-----");

        // start reload cache thread
        es = new ScheduledThreadPoolExecutor(1, new ChunJunThreadFactory("cache-all-reload"));
        es.scheduleAtFixedRate(
                this::reloadCache,
                lookupConfig.getPeriod(),
                lookupConfig.getPeriod(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * ??????????????????
     *
     * @param oneRow ????????????
     * @param tmpCache ???????????????<key ,list<value>>
     */
    protected void buildCache(
            Map<String, Object> oneRow, Map<String, List<Map<String, Object>>> tmpCache) {

        String cacheKey =
                new ArrayList<>(Arrays.asList(keyNames))
                        .stream()
                                .map(oneRow::get)
                                .map(String::valueOf)
                                .collect(Collectors.joining("_"));

        tmpCache.computeIfAbsent(cacheKey, key -> Lists.newArrayList()).add(oneRow);
    }

    /**
     * ?????????????????????????????????
     *
     * @param keyRow ??????join key??????
     */
    @Override
    public Collection<RowData> lookup(RowData keyRow) throws IOException {
        List<String> dataList = Lists.newLinkedList();
        List<RowData> hitRowData = Lists.newArrayList();
        for (int i = 0; i < keyRow.getArity(); i++) {
            dataList.add(String.valueOf(fieldGetters[i].getFieldOrNull(keyRow)));
        }
        String cacheKey = String.join("_", dataList);
        List<Map<String, Object>> cacheList =
                ((Map<String, List<Map<String, Object>>>) (cacheRef.get())).get(cacheKey);
        // ????????????????????????(???/???)??????flink?????????????????????
        if (!CollectionUtils.isEmpty(cacheList)) {
            cacheList.forEach(one -> hitRowData.add(fillData(one)));
        }

        return hitRowData;
    }

    public RowData fillData(Object sideInput) {
        Map<String, Object> cacheInfo = (Map<String, Object>) sideInput;
        GenericRowData row = new GenericRowData(fieldsName.length);
        for (int i = 0; i < fieldsName.length; i++) {
            row.setField(i, cacheInfo.get(fieldsName[i]));
        }
        row.setRowKind(RowKind.INSERT);
        return row;
    }

    /** ???????????? */
    @Override
    public void close() throws Exception {
        if (null != es && !es.isShutdown()) {
            es.shutdown();
        }
    }
}
