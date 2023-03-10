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

package com.dtstack.chunjun.ddl.convent.mysql.parse;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.ImmutableNullableList;

import java.util.List;

public class SqlAlterTableOptions extends SqlAlterTableOperator {
    private final SqlNodeList propertyList;

    public SqlAlterTableOptions(
            SqlParserPos pos, SqlIdentifier tableIdentifier, SqlNodeList propertyList) {
        super(pos, tableIdentifier);
        this.propertyList = propertyList;
    }

    @Override
    public List<SqlNode> getOperandList() {
        return ImmutableNullableList.of(tableIdentifier, propertyList);
    }

    public SqlNodeList getPropertyList() {
        return propertyList;
    }

    @Override
    public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
        //        writer.keyword("ALTER TABLE");
        //        tableIdentifier.unparse(writer, leftPrec, rightPrec);
        List<SqlNode> list = propertyList.getList();
        for (int i = 0; i < list.size(); i++) {
            if (i != 0) {
                writer.print(",");
            }
            list.get(i).unparse(writer, leftPrec, rightPrec);
        }

        writer.newlineAndIndent();
    }
}
