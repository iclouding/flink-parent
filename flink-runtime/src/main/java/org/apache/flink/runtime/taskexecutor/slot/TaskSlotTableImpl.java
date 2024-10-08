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

package org.apache.flink.runtime.taskexecutor.slot;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceBudgetManager;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutor;
import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutor.DummyComponentMainThreadExecutor;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.runtime.taskexecutor.SlotStatus;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/** Default implementation of {@link TaskSlotTable}. */
/*********************
 * clouding 注释: 2021/6/5 19:28
 *   维护这个TaskManager上所有的TaskSlot和Task、Job的关系
 *   通过TimeoutService做task的超时检测
 *   当slot被调度执行一个Task，就会生成一个TaskSlot对象
 *********************/
public class TaskSlotTableImpl<T extends TaskSlotPayload> implements TaskSlotTable<T> {

    private static final Logger LOG = LoggerFactory.getLogger(TaskSlotTableImpl.class);

    /**
     * Number of slots in static slot allocation. If slot is requested with an index, the requested
     * index must within the range of [0, numberSlots). When generating slot report, we should
     * always generate slots with index in [0, numberSlots) even the slot does not exist.
     */
    private final int numberSlots;

    /** Slot resource profile for static slot allocation. */
    private final ResourceProfile defaultSlotResourceProfile;

    /** Page size for memory manager. */
    private final int memoryPageSize;

    /** Timer service used to time out allocated slots. */
    // clouding 注释: 2022/3/26 13:11
    //          负责将已分配的slot(allocated slots) 加入超时检测服务里.TaskExecutor将slot提供给JobMaster,
    //          一旦JobMaster接收,就移除超时检测.否则最终会超时,把该slot状态重置为FREE
    private final TimerService<AllocationID> timerService;

    /** The list of all task slots. */
    // clouding 注释: 2022/3/26 13:14
    //          该TaskExecutor上所有的 TaskSlot 列表
    private final Map<Integer, TaskSlot<T>> taskSlots;

    /** Mapping from allocation id to task slot. */
    // clouding 注释: 2022/3/26 13:15
    //          记录AllocationID 和  TaskSlot的映射关系
    private final Map<AllocationID, TaskSlot<T>> allocatedSlots;

    /** Mapping from execution attempt id to task and task slot. */
    // clouding 注释: 2022/3/26 13:16
    //          记录task的执行id 和 TaskSlotMapping的关系. 通过execution attempt id 查到绑定TaskSlot情况
    private final Map<ExecutionAttemptID, TaskSlotMapping<T>> taskSlotMappings;

    /** Mapping from job id to allocated slots for a job. */
    // clouding 注释: 2022/3/26 13:17
    //          记录job的slot情况
    private final Map<JobID, Set<AllocationID>> slotsPerJob;

    /** Interface for slot actions, such as freeing them or timing them out. */
    // clouding 注释: 2022/3/26 13:17
    //          负载释放slot 和 超时逻辑
    @Nullable private SlotActions slotActions;

    /** The table state. */
    private volatile State state;

    private final ResourceBudgetManager budgetManager;

    /** The closing future is completed when all slot are freed and state is closed. */
    private final CompletableFuture<Void> closingFuture;

    /** {@link ComponentMainThreadExecutor} to schedule internal calls to the main thread. */
    private ComponentMainThreadExecutor mainThreadExecutor =
            new DummyComponentMainThreadExecutor(
                    "TaskSlotTableImpl is not initialized with proper main thread executor, "
                            + "call to TaskSlotTableImpl#start is required");

    /** {@link Executor} for background actions, e.g. verify all managed memory released. */
    private final Executor memoryVerificationExecutor;

    public TaskSlotTableImpl(
            final int numberSlots,
            final ResourceProfile totalAvailableResourceProfile,
            final ResourceProfile defaultSlotResourceProfile,
            final int memoryPageSize,
            final TimerService<AllocationID> timerService,
            final Executor memoryVerificationExecutor) {
        Preconditions.checkArgument(
                0 < numberSlots, "The number of task slots must be greater than 0.");

        this.numberSlots = numberSlots;
        this.defaultSlotResourceProfile = Preconditions.checkNotNull(defaultSlotResourceProfile);
        this.memoryPageSize = memoryPageSize;

        this.taskSlots = new HashMap<>(numberSlots);

        this.timerService = Preconditions.checkNotNull(timerService);

        budgetManager =
                new ResourceBudgetManager(
                        Preconditions.checkNotNull(totalAvailableResourceProfile));

        allocatedSlots = new HashMap<>(numberSlots);

        taskSlotMappings = new HashMap<>(4 * numberSlots);

        slotsPerJob = new HashMap<>(4);

        slotActions = null;
        state = State.CREATED;
        closingFuture = new CompletableFuture<>();

        this.memoryVerificationExecutor = memoryVerificationExecutor;
    }

    @Override
    public void start(
            SlotActions initialSlotActions, ComponentMainThreadExecutor mainThreadExecutor) {
        Preconditions.checkState(
                state == State.CREATED,
                "The %s has to be just created before starting",
                TaskSlotTableImpl.class.getSimpleName());
        this.slotActions = Preconditions.checkNotNull(initialSlotActions);
        this.mainThreadExecutor = Preconditions.checkNotNull(mainThreadExecutor);

        // clouding 注释: 2021/6/5 21:11
        //          一个超时检测服务
        timerService.start(this);

        state = State.RUNNING;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (state == State.CREATED) {
            state = State.CLOSED;
            closingFuture.complete(null);
        } else if (state == State.RUNNING) {
            state = State.CLOSING;
            final FlinkException cause = new FlinkException("Closing task slot table");
            CompletableFuture<Void> cleanupFuture =
                    FutureUtils.waitForAll(
                                    new ArrayList<>(allocatedSlots.values())
                                            .stream()
                                                    .map(slot -> freeSlotInternal(slot, cause))
                                                    .collect(Collectors.toList()))
                            .thenRunAsync(
                                    () -> {
                                        state = State.CLOSED;
                                        timerService.stop();
                                    },
                                    mainThreadExecutor);
            FutureUtils.forward(cleanupFuture, closingFuture);
        }
        return closingFuture;
    }

    @VisibleForTesting
    public boolean isClosed() {
        return state == State.CLOSED;
    }

    @Override
    public Set<AllocationID> getAllocationIdsPerJob(JobID jobId) {
        final Set<AllocationID> allocationIds = slotsPerJob.get(jobId);

        if (allocationIds == null) {
            return Collections.emptySet();
        } else {
            return Collections.unmodifiableSet(allocationIds);
        }
    }

    @Override
    public Set<AllocationID> getActiveTaskAllocationIdsPerJob(JobID jobId) {
        Iterator<TaskSlot<T>> taskSlotIterator = new TaskSlotIterator(jobId, TaskSlotState.ACTIVE);
        Set<AllocationID> allocationIds = new HashSet<>();
        while (taskSlotIterator.hasNext()) {
            allocationIds.add(taskSlotIterator.next().getAllocationId());
        }

        return allocationIds;
    }

    // ---------------------------------------------------------------------
    // Slot report methods
    // ---------------------------------------------------------------------

    @Override
    public SlotReport createSlotReport(ResourceID resourceId) {
        List<SlotStatus> slotStatuses = new ArrayList<>();

        for (int i = 0; i < numberSlots; i++) {
            SlotID slotId = new SlotID(resourceId, i);
            SlotStatus slotStatus;
            if (taskSlots.containsKey(i)) {
                TaskSlot<T> taskSlot = taskSlots.get(i);

                slotStatus =
                        new SlotStatus(
                                slotId,
                                taskSlot.getResourceProfile(),
                                taskSlot.getJobId(),
                                taskSlot.getAllocationId());
            } else {
                slotStatus = new SlotStatus(slotId, defaultSlotResourceProfile, null, null);
            }

            slotStatuses.add(slotStatus);
        }

        for (TaskSlot<T> taskSlot : allocatedSlots.values()) {
            if (taskSlot.getIndex() < 0) {
                SlotID slotID = SlotID.generateDynamicSlotID(resourceId);
                SlotStatus slotStatus =
                        new SlotStatus(
                                slotID,
                                taskSlot.getResourceProfile(),
                                taskSlot.getJobId(),
                                taskSlot.getAllocationId());
                slotStatuses.add(slotStatus);
            }
        }

        final SlotReport slotReport = new SlotReport(slotStatuses);

        return slotReport;
    }

    // ---------------------------------------------------------------------
    // Slot methods
    // ---------------------------------------------------------------------

    @VisibleForTesting
    @Override
    public boolean allocateSlot(
            int index, JobID jobId, AllocationID allocationId, Time slotTimeout) {
        return allocateSlot(index, jobId, allocationId, defaultSlotResourceProfile, slotTimeout);
    }

    // clouding 注释: 2022/6/4 17:48
    //          allocateSlot 方法逻辑:
    //              1. 判断本次slot 是否分配过了. 如果分配过了,就标记失败 返回 false;
    //              2. 否则就执行分配
    @Override
    public boolean allocateSlot(
            int index,
            JobID jobId,
            AllocationID allocationId,
            ResourceProfile resourceProfile,
            Time slotTimeout) {
        // clouding 注释: 2022/3/26 22:45
        //          taskSlot已经启动
        checkRunning();

        Preconditions.checkArgument(index < numberSlots);

        // clouding 注释: 2021/9/20 16:47
        //          校验这个slot是否被分配过
        TaskSlot<T> taskSlot = allocatedSlots.get(allocationId);
        if (taskSlot != null) {
            LOG.info("Allocation ID {} is already allocated in {}.", allocationId, taskSlot);
            return false;
        }

        // clouding 注释: 2021/9/20 16:49
        //          重复的slot申请
        if (taskSlots.containsKey(index)) {
            TaskSlot<T> duplicatedTaskSlot = taskSlots.get(index);
            LOG.info(
                    "Slot with index {} already exist, with resource profile {}, job id {} and allocation id {}.",
                    index,
                    duplicatedTaskSlot.getResourceProfile(),
                    duplicatedTaskSlot.getJobId(),
                    duplicatedTaskSlot.getAllocationId());
            return duplicatedTaskSlot.getJobId().equals(jobId)
                    && duplicatedTaskSlot.getAllocationId().equals(allocationId);
            // clouding 注释: 2021/9/20 16:50
            //          下面这个if，和上面判断不是一样吗？？ 可能是到这里时,重复申请分配拿到了该slot
        } else if (allocatedSlots.containsKey(allocationId)) {
            return true;
        }

        resourceProfile = index >= 0 ? defaultSlotResourceProfile : resourceProfile;
        if (!budgetManager.reserve(resourceProfile)) {
            LOG.info(
                    "Cannot allocate the requested resources. Trying to allocate {}, "
                            + "while the currently remaining available resources are {}, total is {}.",
                    resourceProfile,
                    budgetManager.getAvailableBudget(),
                    budgetManager.getTotalBudget());
            return false;
        }

        // clouding 注释: 2021/9/13 15:35
        //          封装slot对象。
        //          每分配一个Slot，都会封装对应的TaskSlot对象。
        //          TaskSlot对象里包含了这个Task的JobId，allocationId，资源信息
        taskSlot =
                new TaskSlot<>(
                        index,
                        resourceProfile,
                        memoryPageSize,
                        jobId,
                        allocationId,
                        memoryVerificationExecutor);
        if (index >= 0) {
            taskSlots.put(index, taskSlot);
        }

        // update the allocation id to task slot map
        // clouding 注释: 2022/6/4 18:07
        //          记录 allocationId 和 taskSlot 映射关系
        allocatedSlots.put(allocationId, taskSlot);

        // register a timeout for this slot since it's in state allocated
        // clouding 注释: 2022/6/4 18:08
        //          注册一个 超时检测, 如果超时,会把该slot 状态重置
        timerService.registerTimeout(allocationId, slotTimeout.getSize(), slotTimeout.getUnit());

        // add this slot to the set of job slots
        // clouding 注释: 2022/6/4 18:08
        //          记录 jobID 和 slot 的关系
        Set<AllocationID> slots = slotsPerJob.get(jobId);

        if (slots == null) {
            slots = new HashSet<>(4);
            slotsPerJob.put(jobId, slots);
        }

        slots.add(allocationId);

        return true;
    }

    @Override
    public boolean markSlotActive(AllocationID allocationId) throws SlotNotFoundException {
        checkRunning();

        TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null) {
            return markExistingSlotActive(taskSlot);
        } else {
            throw new SlotNotFoundException(allocationId);
        }
    }

    private boolean markExistingSlotActive(TaskSlot<T> taskSlot) {
        if (taskSlot.markActive()) {
            // unregister a potential timeout
            LOG.info("Activate slot {}.", taskSlot.getAllocationId());

            // clouding 注释: 2022/6/4 18:31
            //          从 timerService 移除该slot的超时机制
            timerService.unregisterTimeout(taskSlot.getAllocationId());

            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean markSlotInactive(AllocationID allocationId, Time slotTimeout)
            throws SlotNotFoundException {
        checkStarted();

        TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null) {
            if (taskSlot.markInactive()) {
                // register a timeout to free the slot
                timerService.registerTimeout(
                        allocationId, slotTimeout.getSize(), slotTimeout.getUnit());

                return true;
            } else {
                return false;
            }
        } else {
            throw new SlotNotFoundException(allocationId);
        }
    }

    @Override
    public int freeSlot(AllocationID allocationId, Throwable cause) throws SlotNotFoundException {
        checkStarted();

        TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null) {
            return freeSlotInternal(taskSlot, cause).isDone() ? taskSlot.getIndex() : -1;
        } else {
            throw new SlotNotFoundException(allocationId);
        }
    }

    private CompletableFuture<Void> freeSlotInternal(TaskSlot<T> taskSlot, Throwable cause) {
        AllocationID allocationId = taskSlot.getAllocationId();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Free slot {}.", taskSlot, cause);
        } else {
            LOG.info("Free slot {}.", taskSlot);
        }

        if (taskSlot.isEmpty()) {
            // remove the allocation id to task slot mapping
            allocatedSlots.remove(allocationId);

            // unregister a potential timeout
            timerService.unregisterTimeout(allocationId);

            JobID jobId = taskSlot.getJobId();
            Set<AllocationID> slots = slotsPerJob.get(jobId);

            if (slots == null) {
                throw new IllegalStateException(
                        "There are no more slots allocated for the job "
                                + jobId
                                + ". This indicates a programming bug.");
            }

            slots.remove(allocationId);

            if (slots.isEmpty()) {
                slotsPerJob.remove(jobId);
            }

            taskSlots.remove(taskSlot.getIndex());
            budgetManager.release(taskSlot.getResourceProfile());
        }
        return taskSlot.closeAsync(cause);
    }

    @Override
    public boolean isValidTimeout(AllocationID allocationId, UUID ticket) {
        checkStarted();

        return state == State.RUNNING && timerService.isValid(allocationId, ticket);
    }

    @Override
    public boolean isAllocated(int index, JobID jobId, AllocationID allocationId) {
        TaskSlot<T> taskSlot = taskSlots.get(index);
        if (taskSlot != null) {
            return taskSlot.isAllocated(jobId, allocationId);
        } else if (index < 0) {
            return allocatedSlots.containsKey(allocationId);
        } else {
            return false;
        }
    }

    @Override
    public boolean tryMarkSlotActive(JobID jobId, AllocationID allocationId) {
        TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null && taskSlot.isAllocated(jobId, allocationId)) {
            return markExistingSlotActive(taskSlot);
        } else {
            return false;
        }
    }

    @Override
    public boolean isSlotFree(int index) {
        return !taskSlots.containsKey(index);
    }

    @Override
    public boolean hasAllocatedSlots(JobID jobId) {
        return getAllocatedSlots(jobId).hasNext();
    }

    @Override
    public Iterator<TaskSlot<T>> getAllocatedSlots(JobID jobId) {
        return new TaskSlotIterator(jobId, TaskSlotState.ALLOCATED);
    }

    @Override
    @Nullable
    public JobID getOwningJob(AllocationID allocationId) {
        final TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null) {
            return taskSlot.getJobId();
        } else {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Task methods
    // ---------------------------------------------------------------------

    @Override
    public boolean addTask(T task) throws SlotNotFoundException, SlotNotActiveException {
        checkRunning();
        Preconditions.checkNotNull(task);

        TaskSlot<T> taskSlot = getTaskSlot(task.getAllocationId());

        if (taskSlot != null) {
            if (taskSlot.isActive(task.getJobID(), task.getAllocationId())) {
                if (taskSlot.add(task)) {
                    taskSlotMappings.put(
                            task.getExecutionId(), new TaskSlotMapping<>(task, taskSlot));

                    return true;
                } else {
                    return false;
                }
            } else {
                throw new SlotNotActiveException(task.getJobID(), task.getAllocationId());
            }
        } else {
            throw new SlotNotFoundException(task.getAllocationId());
        }
    }

    @Override
    public T removeTask(ExecutionAttemptID executionAttemptID) {
        checkStarted();

        TaskSlotMapping<T> taskSlotMapping = taskSlotMappings.remove(executionAttemptID);

        if (taskSlotMapping != null) {
            T task = taskSlotMapping.getTask();
            TaskSlot<T> taskSlot = taskSlotMapping.getTaskSlot();

            taskSlot.remove(task.getExecutionId());

            if (taskSlot.isReleasing() && taskSlot.isEmpty()) {
                slotActions.freeSlot(taskSlot.getAllocationId());
            }

            return task;
        } else {
            return null;
        }
    }

    @Override
    public T getTask(ExecutionAttemptID executionAttemptID) {
        TaskSlotMapping<T> taskSlotMapping = taskSlotMappings.get(executionAttemptID);

        if (taskSlotMapping != null) {
            return taskSlotMapping.getTask();
        } else {
            return null;
        }
    }

    @Override
    public Iterator<T> getTasks(JobID jobId) {
        return new PayloadIterator(jobId);
    }

    @Override
    public AllocationID getCurrentAllocation(int index) {
        TaskSlot<T> taskSlot = taskSlots.get(index);
        if (taskSlot == null) {
            return null;
        }
        return taskSlot.getAllocationId();
    }

    @Override
    public MemoryManager getTaskMemoryManager(AllocationID allocationID)
            throws SlotNotFoundException {
        TaskSlot<T> taskSlot = getTaskSlot(allocationID);
        if (taskSlot != null) {
            return taskSlot.getMemoryManager();
        } else {
            throw new SlotNotFoundException(allocationID);
        }
    }

    // ---------------------------------------------------------------------
    // TimeoutListener methods
    // ---------------------------------------------------------------------

    // clouding 注释: 2022/6/4 18:10
    //          超时检测slot
    @Override
    public void notifyTimeout(AllocationID key, UUID ticket) {
        checkStarted();

        if (slotActions != null) {
            slotActions.timeoutSlot(key, ticket);
        }
    }

    // ---------------------------------------------------------------------
    // Internal methods
    // ---------------------------------------------------------------------

    @Nullable
    private TaskSlot<T> getTaskSlot(AllocationID allocationId) {
        Preconditions.checkNotNull(allocationId);

        return allocatedSlots.get(allocationId);
    }

    private void checkRunning() {
        Preconditions.checkState(
                state == State.RUNNING,
                "The %s has to be running.",
                TaskSlotTableImpl.class.getSimpleName());
    }

    private void checkStarted() {
        Preconditions.checkState(
                state != State.CREATED,
                "The %s has to be started (not created).",
                TaskSlotTableImpl.class.getSimpleName());
    }

    // ---------------------------------------------------------------------
    // Static utility classes
    // ---------------------------------------------------------------------

    /** Mapping class between a {@link TaskSlotPayload} and a {@link TaskSlot}. */
    private static final class TaskSlotMapping<T extends TaskSlotPayload> {
        private final T task;
        private final TaskSlot<T> taskSlot;

        private TaskSlotMapping(T task, TaskSlot<T> taskSlot) {
            this.task = Preconditions.checkNotNull(task);
            this.taskSlot = Preconditions.checkNotNull(taskSlot);
        }

        public T getTask() {
            return task;
        }

        public TaskSlot<T> getTaskSlot() {
            return taskSlot;
        }
    }

    /**
     * Iterator over {@link TaskSlot} which fulfill a given state condition and belong to the given
     * job.
     */
    private final class TaskSlotIterator implements Iterator<TaskSlot<T>> {
        private final Iterator<AllocationID> allSlots;
        private final TaskSlotState state;

        private TaskSlot<T> currentSlot;

        private TaskSlotIterator(JobID jobId, TaskSlotState state) {

            Set<AllocationID> allocationIds = slotsPerJob.get(jobId);

            if (allocationIds == null || allocationIds.isEmpty()) {
                allSlots = Collections.emptyIterator();
            } else {
                allSlots = allocationIds.iterator();
            }

            this.state = Preconditions.checkNotNull(state);

            this.currentSlot = null;
        }

        @Override
        public boolean hasNext() {
            while (currentSlot == null && allSlots.hasNext()) {
                AllocationID tempSlot = allSlots.next();

                TaskSlot<T> taskSlot = getTaskSlot(tempSlot);

                if (taskSlot != null && taskSlot.getState() == state) {
                    currentSlot = taskSlot;
                }
            }

            return currentSlot != null;
        }

        @Override
        public TaskSlot<T> next() {
            if (currentSlot != null) {
                TaskSlot<T> result = currentSlot;

                currentSlot = null;

                return result;
            } else {
                while (true) {
                    AllocationID tempSlot;

                    try {
                        tempSlot = allSlots.next();
                    } catch (NoSuchElementException e) {
                        throw new NoSuchElementException("No more task slots.");
                    }

                    TaskSlot<T> taskSlot = getTaskSlot(tempSlot);

                    if (taskSlot != null && taskSlot.getState() == state) {
                        return taskSlot;
                    }
                }
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Cannot remove task slots via this iterator.");
        }
    }

    /** Iterator over all {@link TaskSlotPayload} for a given job. */
    private final class PayloadIterator implements Iterator<T> {
        private final Iterator<TaskSlot<T>> taskSlotIterator;

        private Iterator<T> currentTasks;

        private PayloadIterator(JobID jobId) {
            this.taskSlotIterator = new TaskSlotIterator(jobId, TaskSlotState.ACTIVE);

            this.currentTasks = null;
        }

        @Override
        public boolean hasNext() {
            while ((currentTasks == null || !currentTasks.hasNext())
                    && taskSlotIterator.hasNext()) {
                TaskSlot<T> taskSlot = taskSlotIterator.next();

                currentTasks = taskSlot.getTasks();
            }

            return (currentTasks != null && currentTasks.hasNext());
        }

        @Override
        public T next() {
            while ((currentTasks == null || !currentTasks.hasNext())) {
                TaskSlot<T> taskSlot;

                try {
                    taskSlot = taskSlotIterator.next();
                } catch (NoSuchElementException e) {
                    throw new NoSuchElementException("No more tasks.");
                }

                currentTasks = taskSlot.getTasks();
            }

            return currentTasks.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Cannot remove tasks via this iterator.");
        }
    }

    private enum State {
        CREATED,
        RUNNING,
        CLOSING,
        CLOSED
    }
}
