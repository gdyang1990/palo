// Copyright (c) 2017, Baidu.com, Inc. All Rights Reserved

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.analysis;

import com.baidu.palo.catalog.AccessPrivilege;
import com.baidu.palo.catalog.Catalog;
import com.baidu.palo.catalog.Column;
import com.baidu.palo.catalog.ColumnType;
import com.baidu.palo.catalog.Database;
import com.baidu.palo.catalog.OlapTable;
import com.baidu.palo.catalog.Table;
import com.baidu.palo.cluster.ClusterNamespace;
import com.baidu.palo.common.AnalysisException;
import com.baidu.palo.common.InternalException;
import com.baidu.palo.common.proc.ProcNodeInterface;
import com.baidu.palo.common.proc.ProcResult;
import com.baidu.palo.common.proc.ProcService;
import com.baidu.palo.qe.ShowResultSetMetaData;

import com.google.common.base.Strings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ShowPartitionsStmt extends ShowStmt {
    private static final Logger LOG = LogManager.getLogger(ShowPartitionsStmt.class);

    private String dbName;
    private String tableName;
    private String partitionName;

    private ProcNodeInterface node;

    public ShowPartitionsStmt(TableName tableName, String partitionName) {
        this.dbName = tableName.getDb();
        this.tableName = tableName.getTbl();
        this.partitionName = partitionName;
    }

    public String getDbName() {
        return dbName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getPartitionName() {
        return partitionName;
    }

    public ProcNodeInterface getNode() {
        return node;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException, InternalException {
        if (Strings.isNullOrEmpty(dbName)) {
            dbName = analyzer.getDefaultDb();
            if (Strings.isNullOrEmpty(dbName)) {
                throw new AnalysisException("No db name in show data statement.");
            }
        } else {
            dbName = ClusterNamespace.getDbFullName(getClusterName(), dbName);
        }

        // check access
        if (!analyzer.getCatalog().getUserMgr()
                .checkAccess(analyzer.getUser(), dbName, AccessPrivilege.READ_ONLY)) {
            throw new AnalysisException("No privilege of db(" + dbName + ").");
        }

        Database db = Catalog.getInstance().getDb(dbName);
        if (db == null) {
            throw new AnalysisException("Database[" + dbName + "] does not exist");
        }
        db.readLock();
        try {
            Table table = db.getTable(tableName);
            if (table == null || !(table instanceof OlapTable)) {
                throw new AnalysisException("Table[" + tableName + "] does not exists or is not OLAP table");
            }

            // build proc path
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("/dbs/");
            stringBuilder.append(db.getId());
            stringBuilder.append("/").append(table.getId());
            stringBuilder.append("/").append("partitions");

            LOG.debug("process SHOW PROC '{}';", stringBuilder.toString());

            node = ProcService.getInstance().open(stringBuilder.toString());
            if (node == null) {
                throw new AnalysisException("Failed to show partitions");
            }
        } finally {
            db.readUnlock();
        }
    }

    @Override
    public ShowResultSetMetaData getMetaData() {
        ShowResultSetMetaData.Builder builder = ShowResultSetMetaData.builder();

        ProcResult result = null;
        try {
            result = node.fetchResult();
        } catch (AnalysisException e) {
            return builder.build();
        }

        for (String col : result.getColumnNames()) {
            builder.addColumn(new Column(col, ColumnType.createVarchar(30)));
        }
        return builder.build();
    }

}
