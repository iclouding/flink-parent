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

package org.apache.flink.streaming.api.transformations;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;

import org.apache.flink.shaded.guava18.com.google.common.collect.Lists;

import java.util.Collection;
import java.util.List;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * This transformation represents a change of partitioning of the input elements.
 *
 * <p>This does not create a physical operation, it only affects how upstream operations are
 * connected to downstream operations.
 *
 * @param <T> The type of the elements that result from this {@code PartitionTransformation}
 */
/*********************
 * clouding 注释: 2022/5/14 20:11
 *  	    用以转换输入元素的分区
 *********************/
@Internal
public class PartitionTransformation<T> extends Transformation<T> {

    private final Transformation<T> input;

    private final StreamPartitioner<T> partitioner;

    private final ShuffleMode shuffleMode;

    /**
     * Creates a new {@code PartitionTransformation} from the given input and {@link
     * StreamPartitioner}.
     *
     * @param input The input {@code Transformation}
     * @param partitioner The {@code StreamPartitioner}
     */
    public PartitionTransformation(Transformation<T> input, StreamPartitioner<T> partitioner) {
        this(input, partitioner, ShuffleMode.UNDEFINED);
    }

    /**
     * Creates a new {@code PartitionTransformation} from the given input and {@link
     * StreamPartitioner}.
     *
     * @param input The input {@code Transformation}
     * @param partitioner The {@code StreamPartitioner}
     * @param shuffleMode The {@code ShuffleMode}
     */
    public PartitionTransformation(
            Transformation<T> input, StreamPartitioner<T> partitioner, ShuffleMode shuffleMode) {
        super("Partition", input.getOutputType(), input.getParallelism());
        this.input = input;
        this.partitioner = partitioner;
        this.shuffleMode = checkNotNull(shuffleMode);
    }

    /** Returns the input {@code Transformation} of this {@code SinkTransformation}. */
    public Transformation<T> getInput() {
        return input;
    }

    /**
     * Returns the {@code StreamPartitioner} that must be used for partitioning the elements of the
     * input {@code Transformation}.
     */
    public StreamPartitioner<T> getPartitioner() {
        return partitioner;
    }

    /** Returns the {@link ShuffleMode} of this {@link PartitionTransformation}. */
    public ShuffleMode getShuffleMode() {
        return shuffleMode;
    }

    @Override
    public Collection<Transformation<?>> getTransitivePredecessors() {
        List<Transformation<?>> result = Lists.newArrayList();
        result.add(this);
        result.addAll(input.getTransitivePredecessors());
        return result;
    }
}
