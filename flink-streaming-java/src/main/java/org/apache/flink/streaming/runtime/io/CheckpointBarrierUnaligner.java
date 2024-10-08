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
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointFailureReason;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.io.network.api.CancelCheckpointMarker;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferReceivedListener;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.streaming.runtime.tasks.SubtaskCheckpointCoordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.flink.runtime.checkpoint.CheckpointFailureReason.CHECKPOINT_DECLINED_SUBSUMED;
import static org.apache.flink.util.CloseableIterator.ofElement;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * {@link CheckpointBarrierUnaligner} is used for triggering checkpoint while reading the first
 * barrier and keeping track of the number of received barriers and consumed barriers.
 */
@Internal
@NotThreadSafe
public class CheckpointBarrierUnaligner extends CheckpointBarrierHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointBarrierUnaligner.class);

    private final String taskName;

    /**
     * Tag the state of which input channel has pending in-flight buffers; that is, already received
     * buffers that predate the checkpoint barrier of the current checkpoint.
     */
    // clouding 注释: 2022/7/5 21:00
    //          inflight buffer指的是input channel的receivedBuffer范围内，
    //          在id为当前checkpoint id的barrier之后的所有数据buffer
    private final Map<InputChannelInfo, Boolean> hasInflightBuffers;

    private int numBarrierConsumed;

    /**
     * The checkpoint id to guarantee that we would trigger only one checkpoint when reading the
     * same barrier from different channels.
     *
     * <p>Note: this checkpoint is valid in respect to <b>consumed</b> barriers in contrast to
     * {@link ThreadSafeUnaligner#currentReceivedCheckpointId}.
     */
    private long currentConsumedCheckpointId = -1L;

    /** Encapsulates state that is shared between netty threads and task thread. */
    private final ThreadSafeUnaligner threadSafeUnaligner;

    CheckpointBarrierUnaligner(
            SubtaskCheckpointCoordinator checkpointCoordinator,
            String taskName,
            AbstractInvokable toNotifyOnCheckpoint,
            InputGate... inputGates) {
        super(toNotifyOnCheckpoint);

        this.taskName = taskName;
        hasInflightBuffers =
                Arrays.stream(inputGates)
                        .flatMap(gate -> gate.getChannelInfos().stream())
                        .collect(Collectors.toMap(Function.identity(), info -> false));
        threadSafeUnaligner =
                new ThreadSafeUnaligner(checkNotNull(checkpointCoordinator), this, inputGates);
    }

    /**
     * We still need to trigger checkpoint via {@link
     * ThreadSafeUnaligner#notifyBarrierReceived(CheckpointBarrier, InputChannelInfo)} while reading
     * the first barrier from one channel, because this might happen earlier than the previous async
     * trigger via mailbox by netty thread.
     *
     * <p>Note this is also suitable for the trigger case of local input channel.
     */
    @Override
    public void processBarrier(CheckpointBarrier receivedBarrier, InputChannelInfo channelInfo)
            throws Exception {
        long barrierId = receivedBarrier.getId();
        // clouding 注释: 2022/7/5 21:04
        //          checkpoint id太老了
        if (currentConsumedCheckpointId > barrierId
                || (currentConsumedCheckpointId == barrierId && !isCheckpointPending())) {
            // ignore old and cancelled barriers
            return;
        }
        // clouding 注释: 2022/7/5 21:04
        //          说明新到了一个 checkpoint
        if (currentConsumedCheckpointId < barrierId) {
            currentConsumedCheckpointId = barrierId;
            numBarrierConsumed = 0;
            hasInflightBuffers
                    .entrySet()
                    // clouding 注释: 2022/7/6 11:50
                    //          默认给的 true
                    .forEach(hasInflightBuffer -> hasInflightBuffer.setValue(true));
        }
        if (currentConsumedCheckpointId == barrierId) {
            // clouding 注释: 2022/7/5 21:06
            //          如果当前进行的checkpoint id等于接收到的barrier id，
            //          说明channel中的inflight buffer已经处理完毕, 就设置为false
            hasInflightBuffers.put(channelInfo, false);
            numBarrierConsumed++;
        }
        threadSafeUnaligner.notifyBarrierReceived(receivedBarrier, channelInfo);
    }

    @Override
    public void abortPendingCheckpoint(long checkpointId, CheckpointException exception)
            throws IOException {
        threadSafeUnaligner.tryAbortPendingCheckpoint(checkpointId, exception);

        if (checkpointId > currentConsumedCheckpointId) {
            resetPendingCheckpoint(checkpointId);
        }
    }

    @Override
    public void processCancellationBarrier(CancelCheckpointMarker cancelBarrier) throws Exception {
        final long cancelledId = cancelBarrier.getCheckpointId();
        boolean shouldAbort = threadSafeUnaligner.setCancelledCheckpointId(cancelledId);
        if (shouldAbort) {
            notifyAbort(
                    cancelledId,
                    new CheckpointException(
                            CheckpointFailureReason.CHECKPOINT_DECLINED_ON_CANCELLATION_BARRIER));
        }

        if (cancelledId >= currentConsumedCheckpointId) {
            resetPendingCheckpoint(cancelledId);
            currentConsumedCheckpointId = cancelledId;
        }
    }

    @Override
    public void processEndOfPartition() throws Exception {
        threadSafeUnaligner.onChannelClosed();
        resetPendingCheckpoint(-1L);
    }

    private void resetPendingCheckpoint(long checkpointId) {
        if (isCheckpointPending()) {
            LOG.warn(
                    "{}: Received barrier or EndOfPartition(-1) {} before completing current checkpoint {}. "
                            + "Skipping current checkpoint.",
                    taskName,
                    checkpointId,
                    currentConsumedCheckpointId);

            hasInflightBuffers
                    .entrySet()
                    .forEach(hasInflightBuffer -> hasInflightBuffer.setValue(false));
            numBarrierConsumed = 0;
        }
    }

    @Override
    public long getLatestCheckpointId() {
        return currentConsumedCheckpointId;
    }

    @Override
    public String toString() {
        return String.format("%s: last checkpoint: %d", taskName, currentConsumedCheckpointId);
    }

    @Override
    public void close() throws IOException {
        super.close();
        threadSafeUnaligner.close();
    }

    @Override
    public boolean hasInflightData(long checkpointId, InputChannelInfo channelInfo) {
        if (checkpointId < currentConsumedCheckpointId) {
            return false;
        }
        if (checkpointId > currentConsumedCheckpointId) {
            return true;
        }
        return hasInflightBuffers.get(channelInfo);
    }

    @Override
    public CompletableFuture<Void> getAllBarriersReceivedFuture(long checkpointId) {
        return threadSafeUnaligner.getAllBarriersReceivedFuture(checkpointId);
    }

    @Override
    public Optional<BufferReceivedListener> getBufferReceivedListener() {
        return Optional.of(threadSafeUnaligner);
    }

    @Override
    protected boolean isCheckpointPending() {
        return numBarrierConsumed > 0;
    }

    @VisibleForTesting
    int getNumOpenChannels() {
        return threadSafeUnaligner.getNumOpenChannels();
    }

    @VisibleForTesting
    ThreadSafeUnaligner getThreadSafeUnaligner() {
        return threadSafeUnaligner;
    }

    private void notifyCheckpoint(CheckpointBarrier barrier) throws IOException {
        // ignore the previous triggered checkpoint by netty thread if it was already canceled or
        // aborted before.
        if (barrier.getId() >= threadSafeUnaligner.getCurrentCheckpointId()) {
            super.notifyCheckpoint(barrier, 0);
        }
    }

    @ThreadSafe
    static class ThreadSafeUnaligner implements BufferReceivedListener, Closeable {

        /**
         * Tag the state of which input channel has not received the barrier, such that newly
         * arriving buffers need to be written in the unaligned checkpoint.
         */
        // clouding 注释: 2022/7/6 11:53
        //          用来存储还没有接收到的barrier的inputChannel信息
        private final Map<InputChannelInfo, Boolean> storeNewBuffers;

        /** The number of input channels which has received or processed the barrier. */
        // clouding 注释: 2022/7/6 11:54
        //          接收到的barrier
        private int numBarriersReceived;

        /** A future indicating that all barriers of the a given checkpoint have been read. */
        private CompletableFuture<Void> allBarriersReceivedFuture =
                FutureUtils.completedVoidFuture();

        /**
         * The checkpoint id to guarantee that we would trigger only one checkpoint when reading the
         * same barrier from different channels.
         *
         * <p>Note: this checkpoint is valid in respect to <b>received</b> barriers in contrast to
         * {@link CheckpointBarrierUnaligner#currentConsumedCheckpointId}.
         */
        private long currentReceivedCheckpointId = -1L;

        private int numOpenChannels;

        private final SubtaskCheckpointCoordinator checkpointCoordinator;

        private final CheckpointBarrierUnaligner handler;

        ThreadSafeUnaligner(
                SubtaskCheckpointCoordinator checkpointCoordinator,
                CheckpointBarrierUnaligner handler,
                InputGate... inputGates) {
            storeNewBuffers =
                    Arrays.stream(inputGates)
                            .flatMap(gate -> gate.getChannelInfos().stream())
                            .collect(Collectors.toMap(Function.identity(), info -> false));
            numOpenChannels = storeNewBuffers.size();
            this.checkpointCoordinator = checkpointCoordinator;
            this.handler = handler;
        }

        @Override
        public synchronized void notifyBarrierReceived(
                CheckpointBarrier barrier, InputChannelInfo channelInfo) throws IOException {
            long barrierId = barrier.getId();

            if (currentReceivedCheckpointId < barrierId) {
                // clouding 注释: 2022/7/6 11:58
                //          来了个新的checkpoint
                handleNewCheckpoint(barrier);
                handler.executeInTaskThread(
                        // clouding 注释: 2022/8/7 21:08
                        //          在第一个barrier到达时, 触发 task的 checkpoint操作
                        () -> handler.notifyCheckpoint(barrier), "notifyCheckpoint");
            }

            if (barrierId == currentReceivedCheckpointId && storeNewBuffers.get(channelInfo)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(
                            "{}: Received barrier from channel {} @ {}.",
                            handler.taskName,
                            channelInfo,
                            barrierId);
                }

                // clouding 注释: 2022/8/7 21:07
                //          标识当前 input channel 已到达,不需要继续持久化数据了
                storeNewBuffers.put(channelInfo, false);

                if (++numBarriersReceived == numOpenChannels) {
                    // clouding 注释: 2022/7/6 14:30
                    //          所有的都收到了,会在 ThreadSafeUnaligner.getAllBarriersReceivedFuture 返回
                    allBarriersReceivedFuture.complete(null);
                }
            }
        }

        /**
         * 侦听buffer的监听器
         */
        @Override
        public synchronized void notifyBufferReceived(Buffer buffer, InputChannelInfo channelInfo) {
            // clouding 注释: 2022/7/6 14:12
            //          如果还没收到这个channel的barrier,就写入inputData
            if (storeNewBuffers.get(channelInfo)) {
                checkpointCoordinator
                        .getChannelStateWriter()
                        .addInputData(
                                currentReceivedCheckpointId,
                                channelInfo,
                                ChannelStateWriter.SEQUENCE_NUMBER_UNKNOWN,
                                ofElement(buffer, Buffer::recycleBuffer));
            } else {
                // clouding 注释: 2022/7/6 14:12
                //          否则就回收该buffer
                buffer.recycleBuffer();
            }
        }

        @Override
        public synchronized void close() throws IOException {
            allBarriersReceivedFuture.cancel(false);
        }

        private synchronized void handleNewCheckpoint(CheckpointBarrier barrier)
                throws IOException {
            long barrierId = barrier.getId();
            // clouding 注释: 2022/7/6 11:59
            //          上一个的barrier是否全部到了,如果没有完成,则取消上一个checkpoint
            if (!allBarriersReceivedFuture.isDone()) {
                CheckpointException exception =
                        new CheckpointException(
                                "Barrier id: " + barrierId, CHECKPOINT_DECLINED_SUBSUMED);
                if (isCheckpointPending()) {
                    // we did not complete the current checkpoint, another started before
                    LOG.warn(
                            "{}: Received checkpoint barrier for checkpoint {} before completing current checkpoint {}. "
                                    + "Skipping current checkpoint.",
                            handler.taskName,
                            barrierId,
                            currentReceivedCheckpointId);

                    // let the task know we are not completing this
                    final long currentCheckpointId = currentReceivedCheckpointId;
                    handler.executeInTaskThread(
                            // clouding 注释: 2022/7/6 12:01
                            //          终止上一个checkpoint
                            () -> handler.notifyAbort(currentCheckpointId, exception),
                            "notifyAbort");
                }
                allBarriersReceivedFuture.completeExceptionally(exception);
            }

            // clouding 注释: 2022/7/6 12:00
            //          处理这个新的checkpoint, 设置开始时间
            handler.markCheckpointStart(barrier.getTimestamp());
            currentReceivedCheckpointId = barrierId;
            // clouding 注释: 2022/7/6 12:02
            //          初始化storeNewBuffers，设置所有的input channel都没有收到barrier
            storeNewBuffers.entrySet().forEach(storeNewBuffer -> storeNewBuffer.setValue(true));
            numBarriersReceived = 0;
            // clouding 注释: 2022/7/6 14:29
            //          标记是否所有的input的barrier都收到了
            allBarriersReceivedFuture = new CompletableFuture<>();
            // clouding 注释: 2022/7/6 12:02
            //          开始这个checkpoint
            checkpointCoordinator.initCheckpoint(barrierId, barrier.getCheckpointOptions());
        }

        synchronized CompletableFuture<Void> getAllBarriersReceivedFuture(long checkpointId) {
            if (checkpointId < currentReceivedCheckpointId) {
                return FutureUtils.completedVoidFuture();
            }
            if (checkpointId > currentReceivedCheckpointId) {
                throw new IllegalStateException(
                        "Checkpoint " + checkpointId + " has not been started at all");
            }
            return allBarriersReceivedFuture;
        }

        synchronized void onChannelClosed() throws IOException {
            numOpenChannels--;

            if (resetPendingCheckpoint()) {
                handler.notifyAbort(
                        currentReceivedCheckpointId,
                        new CheckpointException(
                                CheckpointFailureReason.CHECKPOINT_DECLINED_INPUT_END_OF_STREAM));
            }
        }

        synchronized boolean setCancelledCheckpointId(long cancelledId) {
            if (currentReceivedCheckpointId > cancelledId
                    || (currentReceivedCheckpointId == cancelledId && numBarriersReceived == 0)) {
                return false;
            }

            resetPendingCheckpoint();
            currentReceivedCheckpointId = cancelledId;
            return true;
        }

        synchronized void tryAbortPendingCheckpoint(
                long checkpointId, CheckpointException exception) throws IOException {
            if (checkpointId > currentReceivedCheckpointId && resetPendingCheckpoint()) {
                handler.notifyAbort(currentReceivedCheckpointId, exception);
            }
        }

        private boolean resetPendingCheckpoint() {
            if (numBarriersReceived == 0) {
                return false;
            }

            storeNewBuffers.entrySet().forEach(storeNewBuffer -> storeNewBuffer.setValue(false));
            numBarriersReceived = 0;
            return true;
        }

        @VisibleForTesting
        synchronized int getNumOpenChannels() {
            return numOpenChannels;
        }

        @VisibleForTesting
        synchronized long getCurrentCheckpointId() {
            return currentReceivedCheckpointId;
        }

        @VisibleForTesting
        boolean isCheckpointPending() {
            return numBarriersReceived > 0;
        }
    }
}
