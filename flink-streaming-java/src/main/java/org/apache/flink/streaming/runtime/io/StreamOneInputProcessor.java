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

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.annotation.Internal;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.streaming.runtime.io.PushingAsyncDataInput.DataOutput;
import org.apache.flink.streaming.runtime.tasks.OperatorChain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Input reader for {@link org.apache.flink.streaming.runtime.tasks.OneInputStreamTask}.
 *
 * @param <IN> The type of the record that can be read with this record reader.
 */
@Internal
public final class StreamOneInputProcessor<IN> implements StreamInputProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(StreamOneInputProcessor.class);

    private final StreamTaskInput<IN> input;
    private final DataOutput<IN> output;

    private final OperatorChain<?, ?> operatorChain;

    public StreamOneInputProcessor(
            StreamTaskInput<IN> input, DataOutput<IN> output, OperatorChain<?, ?> operatorChain) {

        this.input = checkNotNull(input);
        this.output = checkNotNull(output);
        this.operatorChain = checkNotNull(operatorChain);
    }

    @Override
    public CompletableFuture<?> getAvailableFuture() {
        return input.getAvailableFuture();
    }

    @Override
    public InputStatus processInput() throws Exception {
        // clouding 注释: 2022/5/15 17:38
        //          读取数据的入口, 从输入input,读入到output
        InputStatus status = input.emitNext(output);

        if (status == InputStatus.END_OF_INPUT) {
            operatorChain.endHeadOperatorInput(1);
        }

        return status;
    }

    @Override
    public CompletableFuture<Void> prepareSnapshot(
            ChannelStateWriter channelStateWriter, long checkpointId) throws IOException {
        return input.prepareSnapshot(channelStateWriter, checkpointId);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
