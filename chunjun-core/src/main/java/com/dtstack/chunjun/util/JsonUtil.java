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
package com.dtstack.chunjun.util;

import com.dtstack.chunjun.throwable.ChunJunRuntimeException;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.type.TypeReference;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.MapperFeature;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import com.google.common.collect.Lists;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static java.util.stream.Collectors.toMap;

public class JsonUtil {

    public static final ObjectMapper objectMapper =
            new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
            new TypeReference<Map<String, Object>>() {};

    /**
     * json???????????????????????????
     *
     * @param jsonStr json?????????
     * @param clazz ?????????class
     * @param <T> ??????
     * @return ????????????
     */
    public static <T> T toObject(String jsonStr, Class<T> clazz) {
        try {
            return objectMapper.readValue(jsonStr, clazz);
        } catch (IOException e) {
            throw new ChunJunRuntimeException(
                    "error parse [" + jsonStr + "] to [" + clazz.getName() + "]", e);
        }
    }

    /**
     * json???????????????????????????
     *
     * @param jsonStr json?????????
     * @param clazz ?????????class
     * @param <T> ??????
     * @return ????????????
     */
    public static <T> T toObject(String jsonStr, TypeReference<T> valueTypeRef) {
        try {
            return objectMapper.readValue(jsonStr, valueTypeRef);
        } catch (IOException e) {
            throw new ChunJunRuntimeException(
                    "error parse ["
                            + jsonStr
                            + "] to ["
                            + valueTypeRef.getType().getTypeName()
                            + "]",
                    e);
        }
    }

    /**
     * ???????????????json?????????
     *
     * @param obj ????????????
     * @return json?????????
     */
    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("error parse [" + obj + "] to json", e);
        }
    }

    /**
     * ?????????????????????????????????json?????????(??????????????????)
     *
     * @param obj ????????????
     * @return ??????????????????json?????????
     */
    public static String toPrintJson(Object obj) {
        try {
            Map<String, Object> result =
                    objectMapper.readValue(objectMapper.writeValueAsString(obj), HashMap.class);
            MapUtil.replaceAllElement(result, Lists.newArrayList("pwd", "password"), "******");
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("error parse [" + obj + "] to json", e);
        }
    }

    /**
     * ?????????????????????????????????json?????????(??????????????????)
     *
     * @param obj ????????????
     * @return ??????????????????json?????????
     */
    public static String toFormatJson(Object obj) {
        try {
            Map<String, String> collect =
                    ((Properties) obj)
                            .entrySet().stream()
                                    .collect(
                                            toMap(
                                                    v -> v.getKey().toString(),
                                                    v -> v.getValue().toString()));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(collect);
        } catch (Exception e) {
            throw new RuntimeException("error parse [" + obj + "] to json", e);
        }
    }

    /**
     * ???????????????byte??????
     *
     * @param obj ????????????
     * @return byte??????
     */
    public static byte[] toBytes(Object obj) {
        try {
            return objectMapper.writeValueAsBytes(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("error parse [" + obj + "] to json", e);
        }
    }
}
