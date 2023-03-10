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
package com.dtstack.chunjun.connector.http.inputformat;

import com.dtstack.chunjun.connector.http.client.HttpRequestParam;
import com.dtstack.chunjun.connector.http.client.MetaparamUtils;
import com.dtstack.chunjun.connector.http.client.Strategy;
import com.dtstack.chunjun.connector.http.common.ConstantValue;
import com.dtstack.chunjun.connector.http.common.HttpMethod;
import com.dtstack.chunjun.connector.http.common.HttpRestConfig;
import com.dtstack.chunjun.connector.http.common.MetaParam;
import com.dtstack.chunjun.connector.http.common.ParamType;
import com.dtstack.chunjun.source.format.BaseRichInputFormatBuilder;
import com.dtstack.chunjun.util.GsonUtil;
import com.dtstack.chunjun.util.StringUtil;

import com.google.common.collect.Sets;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toCollection;

public class HttpInputFormatBuilder extends BaseRichInputFormatBuilder<HttpInputFormat> {

    public HttpInputFormatBuilder() {
        super(new HttpInputFormat());
    }

    public void setHttpRestConfig(HttpRestConfig httpRestConfig) {
        super.setConfig(httpRestConfig);
        this.format.setHttpRestConfig(httpRestConfig);
    }

    public void setMetaParams(List<MetaParam> metaColumns) {
        this.format.metaParams = metaColumns;
    }

    public void setMetaBodies(List<MetaParam> metaColumns) {
        this.format.metaBodies = metaColumns;
    }

    public void setMetaHeaders(List<MetaParam> metaColumns) {
        this.format.metaHeaders = metaColumns;
    }

    @Override
    protected void checkFormat() {

        StringBuilder errorMsg = new StringBuilder(128);
        String errorTemplate = "param ???%s??? is not allow null \n";

        if (StringUtils.isBlank(format.httpRestConfig.getUrl())) {
            errorMsg.append(String.format(errorTemplate, "url"));
        }
        if (StringUtils.isBlank(format.httpRestConfig.getRequestMode())) {
            errorMsg.append(String.format(errorTemplate, "requestMode"));
        } else {

            if (!Sets.newHashSet(HttpMethod.GET.name(), HttpMethod.POST.name())
                    .contains(format.httpRestConfig.getRequestMode().toUpperCase(Locale.ENGLISH))) {
                errorMsg.append("requestMode just support GET and POST,we not support ")
                        .append(format.httpRestConfig.getRequestMode())
                        .append(" \n");
            }
        }
        if (StringUtils.isBlank(format.httpRestConfig.getDecode())) {
            errorMsg.append(String.format(errorTemplate, "format")).append("\n");
        }
        if (format.httpRestConfig.getIntervalTime() == null) {
            errorMsg.append(String.format(errorTemplate, "intervalTime")).append("\n");
        } else if (format.httpRestConfig.getIntervalTime() <= 0) {
            errorMsg.append("param ???intervalTime" + "???must more than 0 \n");
        }

        if (StringUtils.isEmpty(format.httpRestConfig.getFieldDelimiter())
                || !ConstantValue.FIELD_DELIMITER.contains(
                        format.httpRestConfig.getFieldDelimiter())) {
            errorMsg.append("we just support fieldDelimiter ")
                    .append(GsonUtil.GSON.toJson(ConstantValue.FIELD_DELIMITER))
                    .append("\n");
        }

        if (errorMsg.length() > 0) {
            throw new IllegalArgumentException(errorMsg.toString());
        }

        // ???????????????????????????key??????????????? key???????????????????????????key?????????????????????1?????????????????????key????????????
        ArrayList<MetaParam> metaParams =
                new ArrayList<>(
                        format.metaParams.size()
                                + format.metaBodies.size()
                                + format.metaHeaders.size());
        metaParams.addAll(format.metaParams);
        metaParams.addAll(format.metaBodies);
        metaParams.addAll(format.metaHeaders);

        StringBuilder sb = new StringBuilder();

        Set<String> allowedRepeatedName =
                new HashSet<>(
                        format.metaParams.size()
                                + format.metaHeaders.size()
                                + format.metaBodies.size());
        allowedRepeatedName.addAll(getAllowedRepeatedName(format.metaParams, sb));
        allowedRepeatedName.addAll(getAllowedRepeatedName(format.metaBodies, sb));
        allowedRepeatedName.addAll(getAllowedRepeatedName(format.metaHeaders, sb));

        // ??????????????? ??????key ??????????????????map??? ???????????????key ?????????????????????????????????
        HttpRequestParam originParam = new HttpRequestParam();
        metaParams.forEach(
                i -> originParam.putValue(i, format.httpRestConfig.getFieldDelimiter(), i));

        // ??????????????????key ??????????????????key ??????????????????key  ???????????????????????? ???????????????????????????????????????key  ??????????????????????????????key ?????????????????????key
        // ???????????????key
        if (CollectionUtils.isNotEmpty(allowedRepeatedName)
                && CollectionUtils.isNotEmpty(format.httpRestConfig.getStrategy())) {
            List<Strategy> errorStrategy = new ArrayList<>();

            format.httpRestConfig
                    .getStrategy()
                    .forEach(
                            i -> {
                                MetaparamUtils.getValueOfMetaParams(
                                                i.getKey(),
                                                null,
                                                format.httpRestConfig,
                                                originParam)
                                        .forEach(
                                                p -> {
                                                    if (allowedRepeatedName.contains(
                                                            p.getAllName())) {
                                                        errorStrategy.add(i);
                                                    }
                                                });

                                MetaparamUtils.getValueOfMetaParams(
                                                i.getValue(),
                                                null,
                                                format.httpRestConfig,
                                                originParam)
                                        .forEach(
                                                p -> {
                                                    if (allowedRepeatedName.contains(
                                                            p.getAllName())) {
                                                        errorStrategy.add(i);
                                                    }
                                                });
                            });

            if (CollectionUtils.isNotEmpty(errorStrategy)) {
                sb.append(StringUtils.join(errorStrategy, ","))
                        .append(
                                " because we do not know specified key or value is nested or non nested")
                        .append("\n");
            }
        }

        if (sb.length() > 0) {
            throw new IllegalArgumentException(errorMsg.append(sb).toString());
        }

        // ??????????????????
        Set<String> anallyIng = new HashSet<>();
        Set<String> analyzed = new HashSet<>();
        metaParams.forEach(
                i ->
                        getValue(
                                originParam,
                                i,
                                true,
                                errorMsg,
                                anallyIng,
                                analyzed,
                                allowedRepeatedName));

        anallyIng.clear();
        analyzed.clear();

        metaParams.forEach(
                i ->
                        getValue(
                                originParam,
                                i,
                                false,
                                errorMsg,
                                anallyIng,
                                analyzed,
                                allowedRepeatedName));

        if (errorMsg.length() > 0) {
            throw new IllegalArgumentException(errorMsg.toString());
        }
    }

    public void getValue(
            HttpRequestParam requestParam,
            MetaParam metaParam,
            boolean first,
            StringBuilder errorMsg,
            Set<String> anallyIng,
            Set<String> analyzed,
            Set<String> sameNames) {

        anallyIng.add(metaParam.getAllName());
        ArrayList<MetaParam> collect =
                MetaparamUtils.getValueOfMetaParams(
                                metaParam.getActualValue(first),
                                metaParam.getIsNest(),
                                format.httpRestConfig,
                                requestParam)
                        .stream()
                        .filter(
                                i ->
                                        !analyzed.contains(i.getAllName())
                                                || i.getParamType().equals(ParamType.BODY)
                                                || i.getParamType().equals(ParamType.PARAM)
                                                || i.getParamType().equals(ParamType.RESPONSE)
                                                || i.getParamType().equals(ParamType.HEADER))
                        .collect(
                                collectingAndThen(
                                        toCollection(
                                                () ->
                                                        new TreeSet<>(
                                                                Comparator.comparing(
                                                                        MetaParam::getAllName))),
                                        ArrayList::new));

        // ??????????????????key ???????????????value|nextValue????????????key ??????????????????????????????key?????????????????????key
        if (CollectionUtils.isNotEmpty(sameNames)) {
            Set<String> keys =
                    collect.stream()
                            .filter(
                                    i ->
                                            i.getParamType().equals(ParamType.HEADER)
                                                    || i.getParamType().equals(ParamType.BODY)
                                                    || i.getParamType().equals(ParamType.PARAM))
                            .map(MetaParam::getAllName)
                            .collect(Collectors.toSet());
            StringBuilder sb = new StringBuilder(128);
            for (String key : keys) {
                if (sameNames.contains(key)) {
                    sb.append(key).append(" ");
                }
            }

            if (sb.length() > 0) {
                throw new IllegalArgumentException(
                        metaParam.getActualValue(first)
                                + " on "
                                + requestParam
                                + "  is error because we do not know "
                                + sb
                                + "is nest or not");
            }
        }

        collect.forEach(
                i1 -> {
                    // value??????????????????response??????  ??????value???????????????????????????key??????????????????response
                    if (first && i1.getParamType().equals(ParamType.RESPONSE)) {
                        errorMsg.append(i1.getAllName())
                                .append(" can not has response variable in value \n");
                        // value??????????????????????????????????????????value???????????????????????????nextValue??????????????????????????????
                    } else if (first && i1.getAllName().equals(metaParam.getAllName())) {
                        errorMsg.append(" The variable in the value of ")
                                .append(i1.getAllName())
                                .append(" can not point to itself \n");
                    } else if (i1.getParamType().equals(ParamType.PARAM)
                            || i1.getParamType().equals(ParamType.BODY)
                            || i1.getParamType().equals(ParamType.HEADER)) {

                        // ???????????????????????????????????? ????????????????????? ???????????????
                        if (!i1.getAllName().equals(metaParam.getAllName())) {
                            if (anallyIng.contains(i1.getAllName())) {
                                errorMsg.append(metaParam.getAllName())
                                        .append(" and ")
                                        .append(i1.getAllName())
                                        .append(" are cyclically dependent \n");
                                // ?????????????????????????????????
                                throw new IllegalArgumentException(errorMsg.toString());
                            } else if (!analyzed.contains(i1.getAllName())) {
                                getValue(
                                        requestParam,
                                        i1,
                                        first,
                                        errorMsg,
                                        anallyIng,
                                        analyzed,
                                        sameNames);
                            }
                        }
                    }
                });
        anallyIng.remove(metaParam.getAllName());
        analyzed.add(metaParam.getAllName());
    }

    /** ?????? metaParams ?????????key???metaParam */
    public List<MetaParam> repeatParamByKey(List<MetaParam> metaParams) {
        ArrayList<MetaParam> data = new ArrayList<>(metaParams.size());
        Map<String, List<MetaParam>> map =
                metaParams.stream().collect(Collectors.groupingBy(MetaParam::getAllName));
        map.forEach(
                (k, v) -> {
                    if (v.size() > 1) {
                        data.addAll(v);
                    }
                });
        return data;
    }

    /** ????????????list??????MetaParam???key???????????? */
    public List<MetaParam> repeatParamByKey(List<MetaParam> left, List<MetaParam> right) {
        ArrayList<MetaParam> data = new ArrayList<>(left.size());
        Set<String> keys = right.stream().map(MetaParam::getKey).collect(Collectors.toSet());
        left.forEach(
                i -> {
                    if (keys.contains(i.getKey())) {
                        data.add(i);
                    }
                });

        return data;
    }

    /**
     * ??????????????????key????????? ?????????????????????key???????????????1??? ??????????????????key
     *
     * @param params ????????????
     * @param sb ????????????
     * @return ???????????????key ???????????????????????? ???body.key1 header.key1
     */
    private Set<String> getAllowedRepeatedName(List<MetaParam> params, StringBuilder sb) {
        // ?????????????????????key????????????
        Map<Boolean, List<MetaParam>> paramMap =
                params.stream().collect(Collectors.groupingBy(MetaParam::getIsNest));
        List<MetaParam> repeatParam = new ArrayList<>(32);

        if (CollectionUtils.isNotEmpty(paramMap.get(true))) {
            repeatParam.addAll(repeatParamByKey(paramMap.get(true)));
        }

        if (CollectionUtils.isNotEmpty(paramMap.get(false))) {
            repeatParam.addAll(repeatParamByKey(paramMap.get(false)));
        }

        if (CollectionUtils.isNotEmpty(repeatParam)) {
            sb.append("key can not repeat,key is ")
                    .append(
                            StringUtils.join(
                                    repeatParam.stream()
                                            .map(MetaParam::getAllName)
                                            .collect(Collectors.toSet()),
                                    ","))
                    .append("\n");
        }

        HashSet<String> allowedSameNames = new HashSet<>();
        // ????????????????????????????????????
        if (paramMap.keySet().size() == 2) {
            if (paramMap.get(true).size() >= paramMap.get(false).size()) {
                repeatParam = repeatParamByKey(paramMap.get(true), paramMap.get(false));
            } else {
                repeatParam = repeatParamByKey(paramMap.get(false), paramMap.get(true));
            }

            repeatParam.forEach(
                    i -> {
                        if (i.getKey()
                                        .split(
                                                StringUtil.escapeExprSpecialWord(
                                                        format.httpRestConfig.getFieldDelimiter()))
                                        .length
                                == 1) {
                            sb.append(i)
                                    .append(
                                            " is repeated and it has only one level When it is a nested key")
                                    .append("\n");
                        } else {
                            allowedSameNames.add(i.getAllName());
                        }
                    });
        }
        return allowedSameNames;
    }
}
