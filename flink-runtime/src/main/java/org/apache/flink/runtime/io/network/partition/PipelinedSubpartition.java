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

package org.apache.flink.runtime.io.network.partition;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateReader;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateReader.ReadResult;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.api.serialization.EventSerializer;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferBuilder;
import org.apache.flink.runtime.io.network.buffer.BufferConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * A pipelined in-memory only subpartition, which can be consumed once.
 *
 * <p>Whenever {@link ResultSubpartition#add(BufferConsumer, boolean)} adds a finished {@link
 * BufferConsumer} or a second {@link BufferConsumer} (in which case we will assume the first one
 * finished), we will {@link PipelinedSubpartitionView#notifyDataAvailable() notify} a read view
 * created via {@link ResultSubpartition#createReadView(BufferAvailabilityListener)} of new data
 * availability. Except by calling {@link #flush()} explicitly, we always only notify when the first
 * finished buffer turns up and then, the reader has to drain the buffers via {@link #pollBuffer()}
 * until its return value shows no more buffers being available. This results in a buffer queue
 * which is either empty or has an unfinished {@link BufferConsumer} left from which the
 * notifications will eventually start again.
 *
 * <p>Explicit calls to {@link #flush()} will force this {@link
 * PipelinedSubpartitionView#notifyDataAvailable() notification} for any {@link BufferConsumer}
 * present in the queue.
 */
public class PipelinedSubpartition extends ResultSubpartition {

    private static final Logger LOG = LoggerFactory.getLogger(PipelinedSubpartition.class);

    // ------------------------------------------------------------------------

    /** All buffers of this subpartition. Access to the buffers is synchronized on this object. */
    // clouding 注释: 2022/1/26 15:29
    //          用来缓冲数据的buffer
    private final ArrayDeque<BufferConsumer> buffers = new ArrayDeque<>();

    /** The number of non-event buffers currently in this subpartition. */
    // clouding 注释: 2022/1/26 15:30
    //          buffer积压的数量
    @GuardedBy("buffers")
    private int buffersInBacklog;

    /** The read view to consume this subpartition. */
    // clouding 注释: 2022/1/26 15:33
    //          消费buffer的reader, netty读取数据的
    private PipelinedSubpartitionView readView;

    /** Flag indicating whether the subpartition has been finished. */
    private boolean isFinished;

    // clouding 注释: 2022/1/26 15:32
    //          当生成数据慢的时候, 用这个标识去刷新数据
    @GuardedBy("buffers")
    private boolean flushRequested;

    /** Flag indicating whether the subpartition has been released. */
    private volatile boolean isReleased;

    /** The total number of buffers (both data and event buffers). */
    private long totalNumberOfBuffers;

    /** The total number of bytes (both data and event buffers). */
    private long totalNumberOfBytes;

    /**
     * The collection of buffers which are spanned over by checkpoint barrier and needs to be
     * persisted for snapshot.
     */
    private final List<Buffer> inflightBufferSnapshot = new ArrayList<>();

    /**
     * Whether this subpartition is blocked by exactly once checkpoint and is waiting for
     * resumption.
     */
    @GuardedBy("buffers")
    private boolean isBlockedByCheckpoint = false;

    // ------------------------------------------------------------------------

    PipelinedSubpartition(int index, ResultPartition parent) {
        super(index, parent);
    }

    @Override
    public void readRecoveredState(ChannelStateReader stateReader)
            throws IOException, InterruptedException {
        boolean recycleBuffer = true;
        for (ReadResult readResult = ReadResult.HAS_MORE_DATA;
                readResult == ReadResult.HAS_MORE_DATA; ) {
            BufferBuilder bufferBuilder =
                    parent.getBufferPool()
                            .requestBufferBuilderBlocking(subpartitionInfo.getSubPartitionIdx());
            BufferConsumer bufferConsumer = bufferBuilder.createBufferConsumer();
            try {
                readResult = stateReader.readOutputData(subpartitionInfo, bufferBuilder);

                // check whether there are some states data filled in this time
                if (bufferConsumer.isDataAvailable()) {
                    add(bufferConsumer, false, false);
                    recycleBuffer = false;
                    bufferBuilder.finish();
                }
            } finally {
                if (recycleBuffer) {
                    bufferConsumer.close();
                }
            }
        }
    }

    @Override
    public boolean add(BufferConsumer bufferConsumer, boolean isPriorityEvent) throws IOException {
        if (isPriorityEvent) {
            // TODO: use readView.notifyPriorityEvent for local channels
            return add(bufferConsumer, false, true);
        }
        return add(bufferConsumer, false, false);
    }

    @Override
    public void finish() throws IOException {
        add(EventSerializer.toBufferConsumer(EndOfPartitionEvent.INSTANCE), true, false);
        LOG.debug("{}: Finished {}.", parent.getOwningTaskName(), this);
    }

    /*********************
     * clouding 注释: 2022/7/23 19:19
     *  	    添加一个 BufferConsumer
     *  	    finish: 代表整个subpartition都完成了, 本次就添加失败了
     *  	    insertAsHead:
     *********************/
    private boolean add(BufferConsumer bufferConsumer, boolean finish, boolean insertAsHead) {
        checkNotNull(bufferConsumer);

        final boolean notifyDataAvailable;
        synchronized (buffers) {
            if (isFinished || isReleased) {
                bufferConsumer.close();
                return false;
            }

            // Add the bufferConsumer and update the stats
            // clouding 注释: 2022/8/7 22:22
            //          加入到了flight data中
            handleAddingBarrier(bufferConsumer, insertAsHead);
            updateStatistics(bufferConsumer);
            // clouding 注释: 2022/7/23 19:22
            //          更新 backlog的值
            increaseBuffersInBacklog(bufferConsumer);
            notifyDataAvailable = insertAsHead || finish || shouldNotifyDataAvailable();

            isFinished |= finish;
        }

        if (notifyDataAvailable) {
            // clouding 注释: 2022/7/23 19:22
            //          通知有数据进来,需要消费了
            notifyDataAvailable();
        }

        return true;
    }

    private void handleAddingBarrier(BufferConsumer bufferConsumer, boolean insertAsHead) {
        assert Thread.holdsLock(buffers);
        if (insertAsHead) {
            checkState(
                    inflightBufferSnapshot.isEmpty(),
                    "Supporting only one concurrent checkpoint in unaligned " + "checkpoints");

            // Meanwhile prepare the collection of in-flight buffers which would be fetched in the
            // next step later.
            for (BufferConsumer buffer : buffers) {
                try (BufferConsumer bc = buffer.copy()) {
                    if (bc.isBuffer()) {
                        inflightBufferSnapshot.add(bc.build());
                    }
                }
            }

            buffers.addFirst(bufferConsumer);
        } else {
            buffers.add(bufferConsumer);
        }
    }

    @Override
    public List<Buffer> requestInflightBufferSnapshot() {
        List<Buffer> snapshot = new ArrayList<>(inflightBufferSnapshot);
        inflightBufferSnapshot.clear();
        return snapshot;
    }

    @Override
    public void release() {
        // view reference accessible outside the lock, but assigned inside the locked scope
        final PipelinedSubpartitionView view;

        synchronized (buffers) {
            if (isReleased) {
                return;
            }

            // Release all available buffers
            for (BufferConsumer buffer : buffers) {
                buffer.close();
            }
            buffers.clear();

            view = readView;
            readView = null;

            // Make sure that no further buffers are added to the subpartition
            isReleased = true;
        }

        LOG.debug("{}: Released {}.", parent.getOwningTaskName(), this);

        if (view != null) {
            view.releaseAllResources();
        }
    }

    @Nullable
    BufferAndBacklog pollBuffer() {
        synchronized (buffers) {
            if (isBlockedByCheckpoint) {
                return null;
            }

            Buffer buffer = null;

            if (buffers.isEmpty()) {
                flushRequested = false;
            }

            while (!buffers.isEmpty()) {
                BufferConsumer bufferConsumer = buffers.peek();

                buffer = bufferConsumer.build();

                checkState(
                        bufferConsumer.isFinished() || buffers.size() == 1,
                        "When there are multiple buffers, an unfinished bufferConsumer can not be at the head of the buffers queue.");

                if (buffers.size() == 1) {
                    // turn off flushRequested flag if we drained all of the available data
                    flushRequested = false;
                }

                if (bufferConsumer.isFinished()) {
                    buffers.pop().close();
                    decreaseBuffersInBacklogUnsafe(bufferConsumer.isBuffer());
                }

                if (buffer.readableBytes() > 0) {
                    break;
                }
                buffer.recycleBuffer();
                buffer = null;
                if (!bufferConsumer.isFinished()) {
                    break;
                }
            }

            if (buffer == null) {
                return null;
            }

            if (buffer.getDataType().isBlockingUpstream()) {
                isBlockedByCheckpoint = true;
            }

            updateStatistics(buffer);
            // Do not report last remaining buffer on buffers as available to read (assuming it's
            // unfinished).
            // It will be reported for reading either on flush or when the number of buffers in the
            // queue
            // will be 2 or more.
            // clouding 注释: 2022/10/16 12:49
            //          构造成 BufferAndBacklog 结构
            return new BufferAndBacklog(
                    buffer,
                    isDataAvailableUnsafe(),
                    getBuffersInBacklog(),
                    isEventAvailableUnsafe());
        }
    }

    void resumeConsumption() {
        synchronized (buffers) {
            checkState(isBlockedByCheckpoint, "Should be blocked by checkpoint.");

            isBlockedByCheckpoint = false;
        }
    }

    @Override
    public int releaseMemory() {
        // The pipelined subpartition does not react to memory release requests.
        // The buffers will be recycled by the consuming task.
        return 0;
    }

    @Override
    public boolean isReleased() {
        return isReleased;
    }

    @Override
    public PipelinedSubpartitionView createReadView(BufferAvailabilityListener availabilityListener)
            throws IOException {
        final boolean notifyDataAvailable;
        synchronized (buffers) {
            checkState(!isReleased);
            checkState(
                    readView == null,
                    "Subpartition %s of is being (or already has been) consumed, "
                            + "but pipelined subpartitions can only be consumed once.",
                    getSubPartitionIndex(),
                    parent.getPartitionId());

            LOG.debug(
                    "{}: Creating read view for subpartition {} of partition {}.",
                    parent.getOwningTaskName(),
                    getSubPartitionIndex(),
                    parent.getPartitionId());

            // clouding 注释: 2022/10/16 12:28
            //          创建 PipelinedSubpartitionView
            readView = new PipelinedSubpartitionView(this, availabilityListener);
            // clouding 注释: 2022/10/16 12:27
            //          buffers不为空,则发送数据
            notifyDataAvailable = !buffers.isEmpty();
        }
        if (notifyDataAvailable) {
            notifyDataAvailable();
        }

        return readView;
    }

    public boolean isAvailable(int numCreditsAvailable) {
        synchronized (buffers) {
            if (numCreditsAvailable > 0) {
                return isDataAvailableUnsafe();
            }

            return isEventAvailableUnsafe();
        }
    }

    private boolean isDataAvailableUnsafe() {
        assert Thread.holdsLock(buffers);

        return !isBlockedByCheckpoint && (flushRequested || getNumberOfFinishedBuffers() > 0);
    }

    private boolean isEventAvailableUnsafe() {
        assert Thread.holdsLock(buffers);

        return !isBlockedByCheckpoint && !buffers.isEmpty() && !buffers.peekFirst().isBuffer();
    }

    // ------------------------------------------------------------------------

    int getCurrentNumberOfBuffers() {
        return buffers.size();
    }

    // ------------------------------------------------------------------------

    @Override
    public String toString() {
        final long numBuffers;
        final long numBytes;
        final boolean finished;
        final boolean hasReadView;

        synchronized (buffers) {
            numBuffers = getTotalNumberOfBuffers();
            numBytes = getTotalNumberOfBytes();
            finished = isFinished;
            hasReadView = readView != null;
        }

        return String.format(
                "PipelinedSubpartition#%d [number of buffers: %d (%d bytes), number of buffers in backlog: %d, finished? %s, read view? %s]",
                getSubPartitionIndex(),
                numBuffers,
                numBytes,
                getBuffersInBacklog(),
                finished,
                hasReadView);
    }

    @Override
    public int unsynchronizedGetNumberOfQueuedBuffers() {
        // since we do not synchronize, the size may actually be lower than 0!
        return Math.max(buffers.size(), 0);
    }

    @Override
    public void flush() {
        final boolean notifyDataAvailable;
        synchronized (buffers) {
            if (buffers.isEmpty() || flushRequested) {
                return;
            }
            // if there is more then 1 buffer, we already notified the reader
            // (at the latest when adding the second buffer)
            notifyDataAvailable =
                    !isBlockedByCheckpoint
                            && buffers.size() == 1
                            && buffers.peek().isDataAvailable();
            flushRequested = buffers.size() > 1 || notifyDataAvailable;
        }
        if (notifyDataAvailable) {
            notifyDataAvailable();
        }
    }

    @Override
    protected long getTotalNumberOfBuffers() {
        return totalNumberOfBuffers;
    }

    @Override
    protected long getTotalNumberOfBytes() {
        return totalNumberOfBytes;
    }

    Throwable getFailureCause() {
        return parent.getFailureCause();
    }

    private void updateStatistics(BufferConsumer buffer) {
        totalNumberOfBuffers++;
    }

    private void updateStatistics(Buffer buffer) {
        totalNumberOfBytes += buffer.getSize();
    }

    @GuardedBy("buffers")
    private void decreaseBuffersInBacklogUnsafe(boolean isBuffer) {
        assert Thread.holdsLock(buffers);
        if (isBuffer) {
            buffersInBacklog--;
        }
    }

    /**
     * Increases the number of non-event buffers by one after adding a non-event buffer into this
     * subpartition.
     */
    @GuardedBy("buffers")
    private void increaseBuffersInBacklog(BufferConsumer buffer) {
        assert Thread.holdsLock(buffers);

        if (buffer != null && buffer.isBuffer()) {
            buffersInBacklog++;
        }
    }

    /**
     * Gets the number of non-event buffers in this subpartition.
     *
     * <p><strong>Beware:</strong> This method should only be used in tests in non-concurrent access
     * scenarios since it does not make any concurrency guarantees.
     */
    @SuppressWarnings("FieldAccessNotGuarded")
    @VisibleForTesting
    public int getBuffersInBacklog() {
        if (flushRequested || isFinished) {
            return buffersInBacklog;
        } else {
            return Math.max(buffersInBacklog - 1, 0);
        }
    }

    private boolean shouldNotifyDataAvailable() {
        // Notify only when we added first finished buffer.
        return readView != null
                && !flushRequested
                && !isBlockedByCheckpoint
                && getNumberOfFinishedBuffers() == 1;
    }

    private void notifyDataAvailable() {
        final PipelinedSubpartitionView readView = this.readView;
        if (readView != null) {
            readView.notifyDataAvailable();
        }
    }

    private int getNumberOfFinishedBuffers() {
        assert Thread.holdsLock(buffers);

        // NOTE: isFinished() is not guaranteed to provide the most up-to-date state here
        // worst-case: a single finished buffer sits around until the next flush() call
        // (but we do not offer stronger guarantees anyway)
        if (buffers.size() == 1 && buffers.peekLast().isFinished()) {
            return 1;
        }

        // We assume that only last buffer is not finished.
        return Math.max(0, buffers.size() - 1);
    }
}
