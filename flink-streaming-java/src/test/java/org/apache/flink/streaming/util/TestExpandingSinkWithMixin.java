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

package org.apache.flink.streaming.util;

import org.apache.flink.api.connector.sink2.Committer;
import org.apache.flink.api.connector.sink2.CommitterInitContext;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.api.connector.sink2.SupportsCommitter;
import org.apache.flink.api.connector.sink2.WriterInitContext;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.streaming.api.connector.sink2.CommittableMessage;
import org.apache.flink.streaming.api.connector.sink2.SupportsPostCommitTopology;
import org.apache.flink.streaming.api.connector.sink2.SupportsPreCommitTopology;
import org.apache.flink.streaming.api.connector.sink2.SupportsPreWriteTopology;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;

import java.io.IOException;

/** A test sink that expands into a simple subgraph. Do not use in runtime. */
public class TestExpandingSinkWithMixin
        implements Sink<Integer>,
                SupportsCommitter<Integer>,
                SupportsPreWriteTopology<Integer>,
                SupportsPreCommitTopology<Integer, Integer>,
                SupportsPostCommitTopology<Integer> {

    @Override
    public void addPostCommitTopology(DataStream<CommittableMessage<Integer>> committables) {
        committables.sinkTo(new DiscardingSink<>());
    }

    @Override
    public DataStream<CommittableMessage<Integer>> addPreCommitTopology(
            DataStream<CommittableMessage<Integer>> committables) {
        return committables.map(value -> value).returns(committables.getType());
    }

    @Override
    public DataStream<Integer> addPreWriteTopology(DataStream<Integer> inputDataStream) {
        return inputDataStream.map(new NoOpIntMap());
    }

    @Override
    public SinkWriter<Integer> createWriter(WriterInitContext context) throws IOException {
        return null;
    }

    @Override
    public SinkWriter<Integer> createWriter(InitContext context) throws IOException {
        return null;
    }

    @Override
    public Committer<Integer> createCommitter(CommitterInitContext context) {
        return null;
    }

    @Override
    public SimpleVersionedSerializer<Integer> getCommittableSerializer() {
        return null;
    }

    @Override
    public SimpleVersionedSerializer<Integer> getWriteResultSerializer() {
        return null;
    }
}
