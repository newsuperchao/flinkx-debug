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

package com.dtstack.chunjun.connector.hive3.source;

import com.dtstack.chunjun.connector.hive3.inputSplit.HdfsOrcInputSplit;
import com.dtstack.chunjun.connector.hive3.util.Hive3Util;
import com.dtstack.chunjun.util.ExceptionUtil;

import org.apache.flink.core.io.InputSplit;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.io.IOConstants;
import org.apache.hadoop.hive.ql.io.orc.OrcFile;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.ql.io.orc.OrcSplit;
import org.apache.hadoop.hive.ql.io.orc.OrcStruct;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.Reporter;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

@Slf4j
public class HdfsTransactionInputFormat extends HdfsOrcInputFormat {

    private static final long serialVersionUID = 7785120717862567959L;

    @Override
    protected InputSplit[] createHdfsSplit(int minNumSplits) {
        log.info("To read hive transaction table, create OrcSplit.");
        hadoopJobConf =
                Hive3Util.getJobConf(hdfsConfig.getHadoopConfig(), hdfsConfig.getDefaultFS());
        // hive3 ??????????????????????????????
        hadoopJobConf.setBoolean(hive_metastoreConstants.TABLE_IS_TRANSACTIONAL, true);
        // ???????????? shaded ????????????????????????,???????????????
        hadoopJobConf.set(
                "fs.hdfs.impl", org.apache.hadoop.hdfs.DistributedFileSystem.class.getName());
        hadoopJobConf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
        // ??????????????? id
        hadoopJobConf.set("hive.txn.valid.txns", Long.MAX_VALUE + ":0");
        FileSystem fileSystem;
        try {
            fileSystem =
                    Hive3Util.getFileSystem(
                            hdfsConfig.getHadoopConfig(), hdfsConfig.getDefaultFS(), null);
            // ??????????????????????????????
            Set<String> allPartitionPath =
                    Hive3Util.getAllPartitionPath(
                            hdfsConfig.getPath(),
                            fileSystem,
                            new HdfsPathFilter(hdfsConfig.getFilterRegex()));
            // ??????????????????
            LinkedList<HdfsOrcInputSplit> allSplit = new LinkedList<>();
            int splitNumber = 0;
            for (String partitionPath : allPartitionPath) {
                OrcInputFormat orcInputFormat = new OrcInputFormat();
                // ????????????????????????????????????????????????????????????????????? acid ???????????????????????????????????????????????????????????????
                hadoopJobConf.set("mapred.input.dir", partitionPath);
                // ??????????????????????????????
                org.apache.hadoop.mapred.InputSplit[] inputSplits =
                        orcInputFormat.getSplits(hadoopJobConf, minNumSplits);
                // ?????? HdfsOrcInputSplit ?????? JM ??? TM ????????????
                for (org.apache.hadoop.mapred.InputSplit inputSplit : inputSplits) {
                    OrcSplit orcSplit = (OrcSplit) inputSplit;
                    if (orcSplit.getLength() > 49) {
                        allSplit.add(new HdfsOrcInputSplit(orcSplit, splitNumber));
                        splitNumber++;
                    }
                }
            }
            return allSplit.toArray(new HdfsOrcInputSplit[0]);
        } catch (Exception e) {
            log.error("hive3 transaction table create split error", e);
            throw new RuntimeException(e);
        }
    }

    protected void orcOpenInternal(InputSplit inputSplit) throws IOException {
        fs = FileSystem.get(hadoopJobConf);
        openAcidRecordReader(inputSplit);
    }

    /** hive ??????????????? RecordReader */
    protected void openAcidRecordReader(InputSplit inputSplit) {
        numReadCounter = getRuntimeContext().getLongCounter("numRead");
        HdfsOrcInputSplit hdfsOrcInputSplit = (HdfsOrcInputSplit) inputSplit;
        OrcSplit orcSplit = null;
        try {
            orcSplit = hdfsOrcInputSplit.getOrcSplit();
        } catch (IOException e) {
            log.error(
                    "Get orc split error, hdfsOrcInputSplit = {}, Exception = {}",
                    hdfsOrcInputSplit,
                    ExceptionUtil.getErrorMessage(e));
        }
        Path path = null;
        if (orcSplit != null) {
            path = orcSplit.getPath();
            // ????????????
            findCurrentPartition(path);
        }
        OrcFile.ReaderOptions readerOptions = OrcFile.readerOptions(hadoopJobConf);
        readerOptions.filesystem(fs);

        org.apache.hadoop.hive.ql.io.orc.Reader reader;
        try {
            reader = OrcFile.createReader(path, readerOptions);
        } catch (IOException e) {
            log.error(
                    "Create reader error, path = {}, Exception = {}",
                    path,
                    ExceptionUtil.getErrorMessage(e));
            throw new RuntimeException("Create reader error.");
        }

        String typeStruct = reader.getObjectInspector().getTypeName();
        if (StringUtils.isEmpty(typeStruct)) {
            throw new RuntimeException("can't retrieve type struct from " + path);
        }

        int startIndex = typeStruct.indexOf("<") + 1;
        int endIndex = typeStruct.lastIndexOf(">");
        typeStruct = typeStruct.substring(startIndex, endIndex);
        startIndex = typeStruct.indexOf("<") + 1;
        endIndex = typeStruct.lastIndexOf(">");
        if (startIndex == 0 || endIndex == -1) {
            log.error(
                    "Please check whether the transaction table option(hiveTransactionTable=true) is used to synchronize non-transactional tables(check hdfs file format,except for base or delta), typeStruct = {}",
                    typeStruct);
            throw new RuntimeException(
                    "Please check whether the transaction table option(hiveTransactionTable=true) is used to synchronize non-transactional tables.(check hdfs file format,except for base or delta)");
        }
        // ???????????????????????? hdfs ???????????????????????????????????????????????????
        typeStruct = typeStruct.substring(startIndex, endIndex);

        if (typeStruct.matches(COMPLEX_FIELD_TYPE_SYMBOL_REGEX)) {
            throw new RuntimeException(
                    "Field types such as array, map, and struct are not supported.");
        }

        List<String> cols = parseColumnAndType(typeStruct);

        fullColNames = new String[cols.size()];
        String[] fullColTypes = new String[cols.size()];

        for (int i = 0; i < cols.size(); ++i) {
            String[] nameTypeTuple2 = cols.get(i).split(":");
            fullColNames[i] = nameTypeTuple2[0];
            fullColTypes[i] = nameTypeTuple2[1];
        }
        final String names = StringUtils.join(fullColNames, ",");
        final String types = StringUtils.join(fullColTypes, ":");
        Properties p = new Properties();
        p.setProperty("columns", names);
        p.setProperty("columns.types", types);

        OrcSerde orcSerde = new OrcSerde();
        orcSerde.initialize(hadoopJobConf, p);

        try {
            this.inspector = (StructObjectInspector) orcSerde.getObjectInspector();
        } catch (SerDeException e) {
            throw new RuntimeException("hive transaction table inspector create failed.");
        }
        // ??? hive ??????????????????????????????
        hadoopJobConf.set(IOConstants.SCHEMA_EVOLUTION_COLUMNS, names);
        // int:bigint:string:float:double:struct<writeId:bigint,bucketId:int,rowId:bigint>
        hadoopJobConf.set(IOConstants.SCHEMA_EVOLUTION_COLUMNS_TYPES, types);
        AcidUtils.setAcidOperationalProperties(hadoopJobConf, true, null);
        try {
            recordReader = inputFormat.getRecordReader(orcSplit, hadoopJobConf, Reporter.NULL);
        } catch (IOException e) {
            throw new RuntimeException("hive transaction table record reader creation failed.");
        }
        key = recordReader.createKey();
        value = recordReader.createValue();
        fields = inspector.getAllStructFieldRefs();
    }

    @Override
    public InputFormat<NullWritable, OrcStruct> createMapredInputFormat() {
        return new OrcInputFormat();
    }
}
