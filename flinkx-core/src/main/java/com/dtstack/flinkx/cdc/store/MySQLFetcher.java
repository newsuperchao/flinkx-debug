package com.dtstack.flinkx.cdc.store;

import org.apache.flink.table.data.RowData;

/**
 * @author tiezhu@dtstack.com
 * @since 2021/12/2 星期四
 */
public class MySQLFetcher extends Fetcher {

    @Override
    public boolean fetch(RowData data) {
        return false;
    }
}
