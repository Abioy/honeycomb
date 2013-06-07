/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 * Copyright 2013 Near Infinity Corporation.
 */


package com.nearinfinity.honeycomb.hbase;

import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.java.quickcheck.collection.Pair;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.UnsignedBytes;
import com.nearinfinity.honeycomb.hbase.generators.RowKeyGenerator;
import com.nearinfinity.honeycomb.hbase.rowkey.IndexRowKey;
import com.nearinfinity.honeycomb.mysql.QueryKey;
import com.nearinfinity.honeycomb.mysql.gen.ColumnType;
import com.nearinfinity.honeycomb.mysql.schema.ColumnSchema;
import com.nearinfinity.honeycomb.mysql.schema.IndexSchema;
import com.nearinfinity.honeycomb.mysql.schema.TableSchema;

public class EncodingTest {
    private static final String COLUMN = "c1";
    private static TableSchema tableSchema =
            new TableSchema(
                    ImmutableList.of(ColumnSchema.builder(COLUMN, ColumnType.LONG).build()),
                    ImmutableList.of(new IndexSchema("i1", ImmutableList.of(COLUMN), false))
            );

    @Test
    public void testAscendingCorrectlySortsLongs() {
        List<Pair<Long, byte[]>> rows = Lists.newArrayList();
        RowKeyGenerator.IndexRowKeyGenerator rowKeyGen =
                RowKeyGenerator.getAscIndexRowKeyGenerator(tableSchema);
        Pair<IndexRowKey, QueryKey> pair;
        for (int i = 0; i < 200; i++) {
            pair = rowKeyGen.nextWithQueryKey();
            if (pair.getSecond().getKeys().get(COLUMN) != null) {
                rows.add(new Pair<Long, byte[]>(
                        pair.getSecond().getKeys().get(COLUMN).getLong(),
                        pair.getFirst().encode()));
            } else {
                i--;
            }
        }

        Collections.sort(rows, new RowComparator());

        for (int i = 1; i < rows.size(); i++) {
            Pair<Long, byte[]> previous = rows.get(i - 1);
            Pair<Long, byte[]> current = rows.get(i);
            assertTrue(previous.getFirst() < current.getFirst());
        }
    }

    @Test
    public void testDescendingCorrectlySortsLong() {
        List<Pair<Long, byte[]>> rows = Lists.newArrayList();
        RowKeyGenerator.IndexRowKeyGenerator rowKeyGen =
                RowKeyGenerator.getDescIndexRowKeyGenerator(tableSchema);
        Pair<IndexRowKey, QueryKey> pair;
        for (int i = 0; i < 200; i++) {
            pair = rowKeyGen.nextWithQueryKey();
            if (pair.getSecond().getKeys().get(COLUMN) != null) {
                rows.add(new Pair<Long, byte[]>(
                    pair.getSecond().getKeys().get(COLUMN).getLong(),
                    pair.getFirst().encode()));
            } else {
                i--;
            }
        }
        Collections.sort(rows, new RowComparator());

        for (int i = 1; i < rows.size(); i++) {
            Pair<Long, byte[]> previous = rows.get(i - 1);
            Pair<Long, byte[]> current = rows.get(i);
            assertTrue(previous.getFirst() > current.getFirst());
        }
    }

    private class RowComparator implements Comparator<Pair<Long, byte[]>> {
        @Override
        public int compare(Pair<Long, byte[]> o1, Pair<Long, byte[]> o2) {
            return UnsignedBytes.lexicographicalComparator().compare(o1.getSecond(), o2.getSecond());
        }
    }
}