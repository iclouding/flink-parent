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

package org.apache.flink.runtime.jobmaster;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.queryablestate.KvStateID;
import org.apache.flink.runtime.accumulators.AccumulatorSnapshot;
import org.apache.flink.runtime.blob.BlobWriter;
import org.apache.flink.runtime.checkpoint.CheckpointMetrics;
import org.apache.flink.runtime.checkpoint.TaskStateSnapshot;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.executiongraph.ArchivedExecutionGraph;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.executiongraph.JobStatusListener;
import org.apache.flink.runtime.heartbeat.HeartbeatListener;
import org.apache.flink.runtime.heartbeat.HeartbeatManager;
import org.apache.flink.runtime.heartbeat.HeartbeatServices;
import org.apache.flink.runtime.heartbeat.HeartbeatTarget;
import org.apache.flink.runtime.heartbeat.NoOpHeartbeatManager;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.io.network.partition.JobMasterPartitionTracker;
import org.apache.flink.runtime.io.network.partition.PartitionTrackerFactory;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.jobmanager.OnCompletionActions;
import org.apache.flink.runtime.jobmanager.PartitionProducerDisposedException;
import org.apache.flink.runtime.jobmaster.factories.JobManagerJobMetricGroupFactory;
import org.apache.flink.runtime.jobmaster.slotpool.Scheduler;
import org.apache.flink.runtime.jobmaster.slotpool.SchedulerFactory;
import org.apache.flink.runtime.jobmaster.slotpool.SlotPool;
import org.apache.flink.runtime.jobmaster.slotpool.SlotPoolFactory;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalListener;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalService;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.messages.FlinkJobNotFoundException;
import org.apache.flink.runtime.messages.checkpoint.DeclineCheckpoint;
import org.apache.flink.runtime.messages.webmonitor.JobDetails;
import org.apache.flink.runtime.metrics.groups.JobManagerJobMetricGroup;
import org.apache.flink.runtime.operators.coordination.CoordinationRequest;
import org.apache.flink.runtime.operators.coordination.CoordinationResponse;
import org.apache.flink.runtime.operators.coordination.OperatorEvent;
import org.apache.flink.runtime.query.KvStateLocation;
import org.apache.flink.runtime.query.UnknownKvStateLocation;
import org.apache.flink.runtime.registration.RegisteredRpcConnection;
import org.apache.flink.runtime.registration.RegistrationResponse;
import org.apache.flink.runtime.registration.RetryingRegistration;
import org.apache.flink.runtime.resourcemanager.ResourceManagerGateway;
import org.apache.flink.runtime.resourcemanager.ResourceManagerId;
import org.apache.flink.runtime.rest.handler.legacy.backpressure.BackPressureStatsTracker;
import org.apache.flink.runtime.rest.handler.legacy.backpressure.OperatorBackPressureStats;
import org.apache.flink.runtime.rest.handler.legacy.backpressure.OperatorBackPressureStatsResponse;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.rpc.FencedRpcEndpoint;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.rpc.RpcUtils;
import org.apache.flink.runtime.rpc.akka.AkkaRpcServiceUtils;
import org.apache.flink.runtime.scheduler.SchedulerNG;
import org.apache.flink.runtime.scheduler.SchedulerNGFactory;
import org.apache.flink.runtime.shuffle.ShuffleMaster;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.taskexecutor.AccumulatorReport;
import org.apache.flink.runtime.taskexecutor.TaskExecutorGateway;
import org.apache.flink.runtime.taskexecutor.slot.SlotOffer;
import org.apache.flink.runtime.taskmanager.TaskExecutionState;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation.ResolutionMode;
import org.apache.flink.runtime.taskmanager.UnresolvedTaskManagerLocation;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.InstantiationUtil;
import org.apache.flink.util.SerializedValue;

import org.slf4j.Logger;

import javax.annotation.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * JobMaster implementation. The job master is responsible for the execution of a single {@link
 * JobGraph}.
 *
 * <p>It offers the following methods as part of its rpc interface to interact with the JobMaster
 * remotely:
 *
 * <ul>
 *   <li>{@link #updateTaskExecutionState} updates the task execution state for given task
 * </ul>
 */
public class JobMaster extends FencedRpcEndpoint<JobMasterId>
        implements JobMasterGateway, JobMasterService {

    /** Default names for Flink's distributed components. */
    public static final String JOB_MANAGER_NAME = "jobmanager";

    // ------------------------------------------------------------------------

    private final JobMasterConfiguration jobMasterConfiguration;

    private final ResourceID resourceId;

    private final JobGraph jobGraph;

    private final Time rpcTimeout;

    private final HighAvailabilityServices highAvailabilityServices;

    private final BlobWriter blobWriter;

    private final HeartbeatServices heartbeatServices;

    private final JobManagerJobMetricGroupFactory jobMetricGroupFactory;

    private final ScheduledExecutorService scheduledExecutorService;

    private final OnCompletionActions jobCompletionActions;

    private final FatalErrorHandler fatalErrorHandler;

    private final ClassLoader userCodeLoader;

    private final SlotPool slotPool;

    private final Scheduler scheduler;

    private final SchedulerNGFactory schedulerNGFactory;

    private final boolean retrieveTaskManagerHostName;

    // --------- BackPressure --------

    private final BackPressureStatsTracker backPressureStatsTracker;

    // --------- ResourceManager --------

    @Nullable private LeaderRetrievalService resourceManagerLeaderRetriever;

    // --------- TaskManagers --------

    private final Map<ResourceID, Tuple2<TaskManagerLocation, TaskExecutorGateway>>
            registeredTaskManagers;

    private final ShuffleMaster<?> shuffleMaster;

    // -------- Mutable fields ---------

    private HeartbeatManager<AccumulatorReport, AllocatedSlotReport> taskManagerHeartbeatManager;

    private HeartbeatManager<Void, Void> resourceManagerHeartbeatManager;

    private SchedulerNG schedulerNG;

    @Nullable private JobManagerJobStatusListener jobStatusListener;

    @Nullable private JobManagerJobMetricGroup jobManagerJobMetricGroup;

    @Nullable private ResourceManagerAddress resourceManagerAddress;

    @Nullable private ResourceManagerConnection resourceManagerConnection;

    @Nullable private EstablishedResourceManagerConnection establishedResourceManagerConnection;

    private Map<String, Object> accumulators;

    private final JobMasterPartitionTracker partitionTracker;

    // ------------------------------------------------------------------------

    public JobMaster(
            RpcService rpcService,
            JobMasterConfiguration jobMasterConfiguration,
            ResourceID resourceId,
            JobGraph jobGraph,
            HighAvailabilityServices highAvailabilityService,
            SlotPoolFactory slotPoolFactory,
            SchedulerFactory schedulerFactory,
            JobManagerSharedServices jobManagerSharedServices,
            HeartbeatServices heartbeatServices,
            JobManagerJobMetricGroupFactory jobMetricGroupFactory,
            OnCompletionActions jobCompletionActions,
            FatalErrorHandler fatalErrorHandler,
            ClassLoader userCodeLoader,
            SchedulerNGFactory schedulerNGFactory,
            ShuffleMaster<?> shuffleMaster,
            PartitionTrackerFactory partitionTrackerFactory)
            throws Exception {

        super(rpcService, AkkaRpcServiceUtils.createRandomName(JOB_MANAGER_NAME), null);

        this.jobMasterConfiguration = checkNotNull(jobMasterConfiguration);
        this.resourceId = checkNotNull(resourceId);
        this.jobGraph = checkNotNull(jobGraph);
        this.rpcTimeout = jobMasterConfiguration.getRpcTimeout();
        this.highAvailabilityServices = checkNotNull(highAvailabilityService);
        this.blobWriter = jobManagerSharedServices.getBlobWriter();
        this.scheduledExecutorService = jobManagerSharedServices.getScheduledExecutorService();
        this.jobCompletionActions = checkNotNull(jobCompletionActions);
        this.fatalErrorHandler = checkNotNull(fatalErrorHandler);
        this.userCodeLoader = checkNotNull(userCodeLoader);
        this.schedulerNGFactory = checkNotNull(schedulerNGFactory);
        this.heartbeatServices = checkNotNull(heartbeatServices);
        this.jobMetricGroupFactory = checkNotNull(jobMetricGroupFactory);
        this.retrieveTaskManagerHostName =
                jobMasterConfiguration
                        .getConfiguration()
                        .getBoolean(JobManagerOptions.RETRIEVE_TASK_MANAGER_HOSTNAME);

        final String jobName = jobGraph.getName();
        final JobID jid = jobGraph.getJobID();

        log.info("Initializing job {} ({}).", jobName, jid);

        this.slotPool = checkNotNull(slotPoolFactory).createSlotPool(jobGraph.getJobID());

        this.scheduler = checkNotNull(schedulerFactory).createScheduler(slotPool);

        this.registeredTaskManagers = new HashMap<>(4);
        this.partitionTracker =
                checkNotNull(partitionTrackerFactory)
                        .create(
                                resourceID -> {
                                    Tuple2<TaskManagerLocation, TaskExecutorGateway>
                                            taskManagerInfo =
                                                    registeredTaskManagers.get(resourceID);
                                    if (taskManagerInfo == null) {
                                        return Optional.empty();
                                    }

                                    return Optional.of(taskManagerInfo.f1);
                                });

        this.backPressureStatsTracker =
                checkNotNull(jobManagerSharedServices.getBackPressureStatsTracker());

        this.shuffleMaster = checkNotNull(shuffleMaster);

        this.jobManagerJobMetricGroup = jobMetricGroupFactory.create(jobGraph);
        // clouding 注释: 2021/10/17 21:45
        //          重要，创建调度器，初始化了很多东西， jobGraph -> executionGraph转换的入口
        this.schedulerNG = createScheduler(jobManagerJobMetricGroup);
        this.jobStatusListener = null;

        this.resourceManagerConnection = null;
        this.establishedResourceManagerConnection = null;

        this.accumulators = new HashMap<>();
        this.taskManagerHeartbeatManager = NoOpHeartbeatManager.getInstance();
        this.resourceManagerHeartbeatManager = NoOpHeartbeatManager.getInstance();
    }

    private SchedulerNG createScheduler(final JobManagerJobMetricGroup jobManagerJobMetricGroup)
            throws Exception {
        return schedulerNGFactory.createInstance(
                log,
                jobGraph,
                backPressureStatsTracker,
                scheduledExecutorService,
                jobMasterConfiguration.getConfiguration(),
                scheduler,
                scheduledExecutorService,
                userCodeLoader,
                highAvailabilityServices.getCheckpointRecoveryFactory(),
                rpcTimeout,
                blobWriter,
                jobManagerJobMetricGroup,
                jobMasterConfiguration.getSlotRequestTimeout(),
                shuffleMaster,
                partitionTracker);
    }

    // ----------------------------------------------------------------------------------------------
    // Lifecycle management
    // ----------------------------------------------------------------------------------------------

    /**
     * Start the rpc service and begin to run the job.
     *
     * @param newJobMasterId The necessary fencing token to run the job
     * @return Future acknowledge if the job could be started. Otherwise the future contains an
     *     exception
     */
    public CompletableFuture<Acknowledge> start(final JobMasterId newJobMasterId) throws Exception {
        // make sure we receive RPC and async calls
        start();

        // clouding 注释: 2021/9/19 22:48
        //          启动的地方 startJobExecution，开始调度执行
        return callAsyncWithoutFencing(
                () -> startJobExecution(newJobMasterId), RpcUtils.INF_TIMEOUT);
    }

    /**
     * Suspending job, all the running tasks will be cancelled, and communication with other
     * components will be disposed.
     *
     * <p>Mostly job is suspended because of the leadership has been revoked, one can be restart
     * this job by calling the {@link #start(JobMasterId)} method once we take the leadership back
     * again.
     *
     * <p>This method is executed asynchronously
     *
     * @param cause The reason of why this job been suspended.
     * @return Future acknowledge indicating that the job has been suspended. Otherwise the future
     *     contains an exception
     */
    public CompletableFuture<Acknowledge> suspend(final Exception cause) {
        CompletableFuture<Acknowledge> suspendFuture =
                callAsyncWithoutFencing(() -> suspendExecution(cause), RpcUtils.INF_TIMEOUT);

        return suspendFuture.whenComplete((acknowledge, throwable) -> stop());
    }

    /** Suspend the job and shutdown all other services including rpc. */
    @Override
    public CompletableFuture<Void> onStop() {
        log.info("Stopping the JobMaster for job {}({}).", jobGraph.getName(), jobGraph.getJobID());

        // make sure there is a graceful exit
        suspendExecution(
                new FlinkException(
                        "Stopping JobMaster for job "
                                + jobGraph.getName()
                                + '('
                                + jobGraph.getJobID()
                                + ")."));

        // shut down will internally release all registered slots
        slotPool.close();

        return CompletableFuture.completedFuture(null);
    }

    // ----------------------------------------------------------------------------------------------
    // RPC methods
    // ----------------------------------------------------------------------------------------------

    @Override
    public CompletableFuture<Acknowledge> cancel(Time timeout) {
        schedulerNG.cancel();

        return CompletableFuture.completedFuture(Acknowledge.get());
    }

    /**
     * Updates the task execution state for a given task.
     *
     * @param taskExecutionState New task execution state for a given task
     * @return Acknowledge the task execution state update
     */
    @Override
    public CompletableFuture<Acknowledge> updateTaskExecutionState(
            final TaskExecutionState taskExecutionState) {
        FlinkException taskExecutionException;
        try {
            checkNotNull(taskExecutionState, "taskExecutionState");

            if (schedulerNG.updateTaskExecutionState(taskExecutionState)) {
                return CompletableFuture.completedFuture(Acknowledge.get());
            } else {
                taskExecutionException =
                        new ExecutionGraphException(
                                "The execution attempt "
                                        + taskExecutionState.getID()
                                        + " was not found.");
            }
        } catch (Exception e) {
            taskExecutionException =
                    new JobMasterException(
                            "Could not update the state of task execution for JobMaster.", e);
            handleJobMasterError(taskExecutionException);
        }
        return FutureUtils.completedExceptionally(taskExecutionException);
    }

    @Override
    public CompletableFuture<SerializedInputSplit> requestNextInputSplit(
            final JobVertexID vertexID, final ExecutionAttemptID executionAttempt) {

        try {
            return CompletableFuture.completedFuture(
                    schedulerNG.requestNextInputSplit(vertexID, executionAttempt));
        } catch (IOException e) {
            log.warn("Error while requesting next input split", e);
            return FutureUtils.completedExceptionally(e);
        }
    }

    @Override
    public CompletableFuture<ExecutionState> requestPartitionState(
            final IntermediateDataSetID intermediateResultId,
            final ResultPartitionID resultPartitionId) {

        try {
            return CompletableFuture.completedFuture(
                    schedulerNG.requestPartitionState(intermediateResultId, resultPartitionId));
        } catch (PartitionProducerDisposedException e) {
            log.info("Error while requesting partition state", e);
            return FutureUtils.completedExceptionally(e);
        }
    }

    @Override
    public CompletableFuture<Acknowledge> scheduleOrUpdateConsumers(
            final ResultPartitionID partitionID, final Time timeout) {

        schedulerNG.scheduleOrUpdateConsumers(partitionID);
        return CompletableFuture.completedFuture(Acknowledge.get());
    }

    /**
     * todo: 某个taskManager挂机后的流程
     * @param resourceID identifying the TaskManager to disconnect
     * @param cause for the disconnection of the TaskManager
     * @return
     */
    @Override
    public CompletableFuture<Acknowledge> disconnectTaskManager(
            final ResourceID resourceID, final Exception cause) {
        log.debug("Disconnect TaskExecutor {} because: {}", resourceID, cause.getMessage());

        taskManagerHeartbeatManager.unmonitorTarget(resourceID);
        slotPool.releaseTaskManager(resourceID, cause);
        partitionTracker.stopTrackingPartitionsFor(resourceID);

        Tuple2<TaskManagerLocation, TaskExecutorGateway> taskManagerConnection =
                registeredTaskManagers.remove(resourceID);

        if (taskManagerConnection != null) {
            taskManagerConnection.f1.disconnectJobManager(jobGraph.getJobID(), cause);
        }

        return CompletableFuture.completedFuture(Acknowledge.get());
    }

    // TODO: This method needs a leader session ID
    @Override
    public void acknowledgeCheckpoint(
            final JobID jobID,
            final ExecutionAttemptID executionAttemptID,
            final long checkpointId,
            final CheckpointMetrics checkpointMetrics,
            final TaskStateSnapshot checkpointState) {

        schedulerNG.acknowledgeCheckpoint(
                jobID, executionAttemptID, checkpointId, checkpointMetrics, checkpointState);
    }

    // TODO: This method needs a leader session ID
    @Override
    public void declineCheckpoint(DeclineCheckpoint decline) {
        schedulerNG.declineCheckpoint(decline);
    }

    @Override
    public CompletableFuture<Acknowledge> sendOperatorEventToCoordinator(
            final ExecutionAttemptID task,
            final OperatorID operatorID,
            final SerializedValue<OperatorEvent> serializedEvent) {

        try {
            final OperatorEvent evt = serializedEvent.deserializeValue(userCodeLoader);
            schedulerNG.deliverOperatorEventToCoordinator(task, operatorID, evt);
            return CompletableFuture.completedFuture(Acknowledge.get());
        } catch (Exception e) {
            return FutureUtils.completedExceptionally(e);
        }
    }

    @Override
    public CompletableFuture<KvStateLocation> requestKvStateLocation(
            final JobID jobId, final String registrationName) {
        try {
            return CompletableFuture.completedFuture(
                    schedulerNG.requestKvStateLocation(jobId, registrationName));
        } catch (UnknownKvStateLocation | FlinkJobNotFoundException e) {
            log.info("Error while request key-value state location", e);
            return FutureUtils.completedExceptionally(e);
        }
    }

    @Override
    public CompletableFuture<Acknowledge> notifyKvStateRegistered(
            final JobID jobId,
            final JobVertexID jobVertexId,
            final KeyGroupRange keyGroupRange,
            final String registrationName,
            final KvStateID kvStateId,
            final InetSocketAddress kvStateServerAddress) {

        try {
            schedulerNG.notifyKvStateRegistered(
                    jobId,
                    jobVertexId,
                    keyGroupRange,
                    registrationName,
                    kvStateId,
                    kvStateServerAddress);
            return CompletableFuture.completedFuture(Acknowledge.get());
        } catch (FlinkJobNotFoundException e) {
            log.info("Error while receiving notification about key-value state registration", e);
            return FutureUtils.completedExceptionally(e);
        }
    }

    @Override
    public CompletableFuture<Acknowledge> notifyKvStateUnregistered(
            JobID jobId,
            JobVertexID jobVertexId,
            KeyGroupRange keyGroupRange,
            String registrationName) {
        try {
            schedulerNG.notifyKvStateUnregistered(
                    jobId, jobVertexId, keyGroupRange, registrationName);
            return CompletableFuture.completedFuture(Acknowledge.get());
        } catch (FlinkJobNotFoundException e) {
            log.info("Error while receiving notification about key-value state de-registration", e);
            return FutureUtils.completedExceptionally(e);
        }
    }

    /**
     * TaskExecutor 在准备好slot的以后，会通知JobMaster自己的slot情况。
     * @param taskManagerId identifying the task manager
     * @param slots to offer to the job manager
     * @param timeout for the rpc call
     * @return
     */
    @Override
    public CompletableFuture<Collection<SlotOffer>> offerSlots(
            final ResourceID taskManagerId, final Collection<SlotOffer> slots, final Time timeout) {

        // clouding 注释: 2022/3/25 21:14
        //          判断taskManager 有没有注册过,没有注册的直接报错
        Tuple2<TaskManagerLocation, TaskExecutorGateway> taskManager =
                registeredTaskManagers.get(taskManagerId);

        // clouding 注释: 2022/3/25 21:15
        //          没有注册过的,报错
        if (taskManager == null) {
            return FutureUtils.completedExceptionally(
                    new Exception("Unknown TaskManager " + taskManagerId));
        }

        final TaskManagerLocation taskManagerLocation = taskManager.f0;
        final TaskExecutorGateway taskExecutorGateway = taskManager.f1;

        final RpcTaskManagerGateway rpcTaskManagerGateway =
                new RpcTaskManagerGateway(taskExecutorGateway, getFencingToken());

        // clouding 注释: 2022/3/25 21:15
        //          使用 slotPool 来处理接收的slots
        return CompletableFuture.completedFuture(
                slotPool.offerSlots(taskManagerLocation, rpcTaskManagerGateway, slots));
    }

    @Override
    public void failSlot(
            final ResourceID taskManagerId,
            final AllocationID allocationId,
            final Exception cause) {

        if (registeredTaskManagers.containsKey(taskManagerId)) {
            internalFailAllocation(allocationId, cause);
        } else {
            log.warn(
                    "Cannot fail slot "
                            + allocationId
                            + " because the TaskManager "
                            + taskManagerId
                            + " is unknown.");
        }
    }

    private void internalFailAllocation(AllocationID allocationId, Exception cause) {
        final Optional<ResourceID> resourceIdOptional =
                slotPool.failAllocation(allocationId, cause);
        resourceIdOptional.ifPresent(
                taskManagerId -> {
                    if (!partitionTracker.isTrackingPartitionsFor(taskManagerId)) {
                        releaseEmptyTaskManager(taskManagerId);
                    }
                });
    }

    private void releaseEmptyTaskManager(ResourceID resourceId) {
        disconnectTaskManager(
                resourceId,
                new FlinkException(
                        String.format("No more slots registered at JobMaster %s.", resourceId)));
    }

    @Override
    public CompletableFuture<RegistrationResponse> registerTaskManager(
            final String taskManagerRpcAddress,
            final UnresolvedTaskManagerLocation unresolvedTaskManagerLocation,
            final JobID jobId,
            final Time timeout) {

        if (!jobGraph.getJobID().equals(jobId)) {
            log.debug(
                    "Rejecting TaskManager registration attempt because of wrong job id {}.",
                    jobId);
            return CompletableFuture.completedFuture(
                    new JMTMRegistrationRejection(
                            String.format(
                                    "The JobManager is not responsible for job %s. Maybe the TaskManager used outdated connection information.",
                                    jobId)));
        }

        final TaskManagerLocation taskManagerLocation;
        try {
            if (retrieveTaskManagerHostName) {
                taskManagerLocation =
                        TaskManagerLocation.fromUnresolvedLocation(
                                unresolvedTaskManagerLocation, ResolutionMode.RETRIEVE_HOST_NAME);
            } else {
                taskManagerLocation =
                        TaskManagerLocation.fromUnresolvedLocation(
                                unresolvedTaskManagerLocation, ResolutionMode.USE_IP_ONLY);
            }
        } catch (Throwable throwable) {
            final String errMsg =
                    String.format(
                            "Could not accept TaskManager registration. TaskManager address %s cannot be resolved. %s",
                            unresolvedTaskManagerLocation.getExternalAddress(),
                            throwable.getMessage());
            log.error(errMsg);
            return CompletableFuture.completedFuture(
                    new RegistrationResponse.Failure(new FlinkException(errMsg, throwable)));
        }

        final ResourceID taskManagerId = taskManagerLocation.getResourceID();

        if (registeredTaskManagers.containsKey(taskManagerId)) {
            final RegistrationResponse response = new JMTMRegistrationSuccess(resourceId);
            return CompletableFuture.completedFuture(response);
        } else {
            return getRpcService()
                    .connect(taskManagerRpcAddress, TaskExecutorGateway.class)
                    .handleAsync(
                            (TaskExecutorGateway taskExecutorGateway, Throwable throwable) -> {
                                if (throwable != null) {
                                    return new RegistrationResponse.Failure(throwable);
                                }

                                slotPool.registerTaskManager(taskManagerId);
                                registeredTaskManagers.put(
                                        taskManagerId,
                                        Tuple2.of(taskManagerLocation, taskExecutorGateway));

                                // monitor the task manager as heartbeat target
                                taskManagerHeartbeatManager.monitorTarget(
                                        taskManagerId,
                                        new HeartbeatTarget<AllocatedSlotReport>() {
                                            @Override
                                            public void receiveHeartbeat(
                                                    ResourceID resourceID,
                                                    AllocatedSlotReport payload) {
                                                // the task manager will not request heartbeat, so
                                                // this method will never be called currently
                                            }

                                            @Override
                                            public void requestHeartbeat(
                                                    ResourceID resourceID,
                                                    AllocatedSlotReport allocatedSlotReport) {
                                                taskExecutorGateway.heartbeatFromJobManager(
                                                        resourceID, allocatedSlotReport);
                                            }
                                        });

                                return new JMTMRegistrationSuccess(resourceId);
                            },
                            getMainThreadExecutor());
        }
    }

    @Override
    public void disconnectResourceManager(
            final ResourceManagerId resourceManagerId, final Exception cause) {

        if (isConnectingToResourceManager(resourceManagerId)) {
            reconnectToResourceManager(cause);
        }
    }

    private boolean isConnectingToResourceManager(ResourceManagerId resourceManagerId) {
        return resourceManagerAddress != null
                && resourceManagerAddress.getResourceManagerId().equals(resourceManagerId);
    }

    @Override
    public void heartbeatFromTaskManager(
            final ResourceID resourceID, AccumulatorReport accumulatorReport) {
        taskManagerHeartbeatManager.receiveHeartbeat(resourceID, accumulatorReport);
    }

    @Override
    public void heartbeatFromResourceManager(final ResourceID resourceID) {
        resourceManagerHeartbeatManager.requestHeartbeat(resourceID, null);
    }

    @Override
    public CompletableFuture<JobDetails> requestJobDetails(Time timeout) {
        return CompletableFuture.completedFuture(schedulerNG.requestJobDetails());
    }

    @Override
    public CompletableFuture<JobStatus> requestJobStatus(Time timeout) {
        return CompletableFuture.completedFuture(schedulerNG.requestJobStatus());
    }

    @Override
    public CompletableFuture<ArchivedExecutionGraph> requestJob(Time timeout) {
        return CompletableFuture.completedFuture(schedulerNG.requestJob());
    }

    @Override
    public CompletableFuture<String> triggerSavepoint(
            @Nullable final String targetDirectory, final boolean cancelJob, final Time timeout) {

        return schedulerNG.triggerSavepoint(targetDirectory, cancelJob);
    }

    @Override
    public CompletableFuture<String> stopWithSavepoint(
            @Nullable final String targetDirectory, final boolean terminate, final Time timeout) {

        return schedulerNG.stopWithSavepoint(targetDirectory, terminate);
    }

    @Override
    public CompletableFuture<OperatorBackPressureStatsResponse> requestOperatorBackPressureStats(
            final JobVertexID jobVertexId) {
        try {
            final Optional<OperatorBackPressureStats> operatorBackPressureStats =
                    schedulerNG.requestOperatorBackPressureStats(jobVertexId);
            return CompletableFuture.completedFuture(
                    OperatorBackPressureStatsResponse.of(operatorBackPressureStats.orElse(null)));
        } catch (FlinkException e) {
            log.info("Error while requesting operator back pressure stats", e);
            return FutureUtils.completedExceptionally(e);
        }
    }

    @Override
    public void notifyAllocationFailure(AllocationID allocationID, Exception cause) {
        internalFailAllocation(allocationID, cause);
    }

    @Override
    public CompletableFuture<Object> updateGlobalAggregate(
            String aggregateName, Object aggregand, byte[] serializedAggregateFunction) {

        AggregateFunction aggregateFunction = null;
        try {
            aggregateFunction =
                    InstantiationUtil.deserializeObject(
                            serializedAggregateFunction, userCodeLoader);
        } catch (Exception e) {
            log.error("Error while attempting to deserialize user AggregateFunction.");
            return FutureUtils.completedExceptionally(e);
        }

        Object accumulator = accumulators.get(aggregateName);
        if (null == accumulator) {
            accumulator = aggregateFunction.createAccumulator();
        }
        accumulator = aggregateFunction.add(aggregand, accumulator);
        accumulators.put(aggregateName, accumulator);
        return CompletableFuture.completedFuture(aggregateFunction.getResult(accumulator));
    }

    @Override
    public CompletableFuture<CoordinationResponse> deliverCoordinationRequestToCoordinator(
            OperatorID operatorId,
            SerializedValue<CoordinationRequest> serializedRequest,
            Time timeout) {
        try {
            CoordinationRequest request = serializedRequest.deserializeValue(userCodeLoader);
            return schedulerNG.deliverCoordinationRequestToCoordinator(operatorId, request);
        } catch (Exception e) {
            return FutureUtils.completedExceptionally(e);
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Internal methods
    // ----------------------------------------------------------------------------------------------

    // -- job starting and stopping
    // -----------------------------------------------------------------

    private Acknowledge startJobExecution(JobMasterId newJobMasterId) throws Exception {

        validateRunsInMainThread();

        checkNotNull(newJobMasterId, "The new JobMasterId must not be null.");

        if (Objects.equals(getFencingToken(), newJobMasterId)) {
            log.info("Already started the job execution with JobMasterId {}.", newJobMasterId);

            return Acknowledge.get();
        }

        setNewFencingToken(newJobMasterId);

        // clouding 注释: 2021/9/19 22:48
        //          初始化一些服务组件。比如
        startJobMasterServices();

        log.info(
                "Starting execution of job {} ({}) under job master id {}.",
                jobGraph.getName(),
                jobGraph.getJobID(),
                newJobMasterId);

        // clouding 注释: 2021/9/19 22:48
        //          开始调度执行。调度StreamTask执行。
        //          1. slot的申请， ---> allocateSlots
        //          2. task的部署运行 --> deploySlots
        resetAndStartScheduler();

        return Acknowledge.get();
    }

    private void startJobMasterServices() throws Exception {
        // clouding 注释: 2021/9/19 22:49
        //          jobMaster的心跳服务
        //          1. jobMaster和resourceManager的心跳 2. jobmaster和taskmanager的心跳
        startHeartbeatServices();

        // start the slot pool make sure the slot pool now accepts messages for this leader
        slotPool.start(getFencingToken(), getAddress(), getMainThreadExecutor());
        scheduler.start(getMainThreadExecutor());

        // TODO: Remove once the ZooKeeperLeaderRetrieval returns the stored address upon start
        // try to reconnect to previously known leader
        /*********************
         * clouding 注释: 2021/9/19 23:22
         *   连接ResourceManager，注册这个JobMaster，申请slot
         *********************/
        reconnectToResourceManager(new FlinkException("Starting JobMaster component."));

        // job is ready to go, try to establish connection with resource manager
        //   - activate leader retrieval for the resource manager
        //   - on notification of the leader, the connection will be established and
        //     the slot pool will start requesting slots
        if (resourceManagerLeaderRetriever != null) {
            resourceManagerLeaderRetriever.stop();
        }
        resourceManagerLeaderRetriever =
                highAvailabilityServices.getResourceManagerLeaderRetriever();
        resourceManagerLeaderRetriever.start(new ResourceManagerLeaderListener());
    }

    private void setNewFencingToken(JobMasterId newJobMasterId) {
        if (getFencingToken() != null) {
            log.info(
                    "Restarting old job with JobMasterId {}. The new JobMasterId is {}.",
                    getFencingToken(),
                    newJobMasterId);

            // first we have to suspend the current execution
            suspendExecution(
                    new FlinkException(
                            "Old job with JobMasterId "
                                    + getFencingToken()
                                    + " is restarted with a new JobMasterId "
                                    + newJobMasterId
                                    + '.'));
        }

        // set new leader id
        setFencingToken(newJobMasterId);
    }

    /**
     * Suspending job, all the running tasks will be cancelled, and communication with other
     * components will be disposed.
     *
     * <p>Mostly job is suspended because of the leadership has been revoked, one can be restart
     * this job by calling the {@link #start(JobMasterId)} method once we take the leadership back
     * again.
     *
     * @param cause The reason of why this job been suspended.
     */
    private Acknowledge suspendExecution(final Exception cause) {
        validateRunsInMainThread();

        if (getFencingToken() == null) {
            log.debug("Job has already been suspended or shutdown.");
            return Acknowledge.get();
        }

        // not leader anymore --> set the JobMasterId to null
        setFencingToken(null);

        try {
            if (resourceManagerLeaderRetriever != null) {
                resourceManagerLeaderRetriever.stop();
                resourceManagerLeaderRetriever = null;
            }
            resourceManagerAddress = null;
        } catch (Throwable t) {
            log.warn("Failed to stop resource manager leader retriever when suspending.", t);
        }

        suspendAndClearSchedulerFields(cause);

        // disconnect from all registered TaskExecutors
        final Set<ResourceID> taskManagerResourceIds =
                new HashSet<>(registeredTaskManagers.keySet());

        for (ResourceID taskManagerResourceId : taskManagerResourceIds) {
            disconnectTaskManager(taskManagerResourceId, cause);
        }

        // the slot pool stops receiving messages and clears its pooled slots
        slotPool.suspend();

        // disconnect from resource manager:
        closeResourceManagerConnection(cause);

        stopHeartbeatServices();

        return Acknowledge.get();
    }

    private void stopHeartbeatServices() {
        taskManagerHeartbeatManager.stop();
        resourceManagerHeartbeatManager.stop();
    }

    private void startHeartbeatServices() {
        /*********************
        * clouding 注释: 2022/1/9 22:43
        *  	     创建TaskManager的心跳管理，执行HeartbeatManagerSenderImpl, 创建后就直接启动了
        *********************/
        taskManagerHeartbeatManager =
                heartbeatServices.createHeartbeatManagerSender(
                        resourceId,
                        // clouding 注释: 2022/1/9 22:49
                        //          心跳管理监听器
                        new TaskManagerHeartbeatListener(),
                        getMainThreadExecutor(),
                        log);

        resourceManagerHeartbeatManager =
                heartbeatServices.createHeartbeatManager(
                        resourceId,
                        new ResourceManagerHeartbeatListener(),
                        getMainThreadExecutor(),
                        log);
    }

    private void assignScheduler(
            SchedulerNG newScheduler, JobManagerJobMetricGroup newJobManagerJobMetricGroup) {
        validateRunsInMainThread();
        checkState(schedulerNG.requestJobStatus().isTerminalState());
        checkState(jobManagerJobMetricGroup == null);

        schedulerNG = newScheduler;
        jobManagerJobMetricGroup = newJobManagerJobMetricGroup;
    }

    private void resetAndStartScheduler() throws Exception {
        validateRunsInMainThread();

        final CompletableFuture<Void> schedulerAssignedFuture;

        if (schedulerNG.requestJobStatus() == JobStatus.CREATED) {
            schedulerAssignedFuture = CompletableFuture.completedFuture(null);
            schedulerNG.setMainThreadExecutor(getMainThreadExecutor());
        } else {
            suspendAndClearSchedulerFields(
                    new FlinkException(
                            "ExecutionGraph is being reset in order to be rescheduled."));
            final JobManagerJobMetricGroup newJobManagerJobMetricGroup =
                    jobMetricGroupFactory.create(jobGraph);
            final SchedulerNG newScheduler = createScheduler(newJobManagerJobMetricGroup);

            schedulerAssignedFuture =
                    schedulerNG
                            .getTerminationFuture()
                            .handle(
                                    (ignored, throwable) -> {
                                        newScheduler.setMainThreadExecutor(getMainThreadExecutor());
                                        assignScheduler(newScheduler, newJobManagerJobMetricGroup);
                                        return null;
                                    });
        }

        // clouding 注释: 2021/9/20 14:48
        //          开始调度，也就是申请slot和部署job
        FutureUtils.assertNoException(schedulerAssignedFuture.thenRun(this::startScheduling));
    }

    private void startScheduling() {
        checkState(jobStatusListener == null);
        // register self as job status change listener
        jobStatusListener = new JobManagerJobStatusListener();
        schedulerNG.registerJobStatusListener(jobStatusListener);

        // clouding 注释: 2021/9/20 14:49
        //          schedulerNG =
        schedulerNG.startScheduling();
    }

    private void suspendAndClearSchedulerFields(Exception cause) {
        suspendScheduler(cause);
        clearSchedulerFields();
    }

    private void suspendScheduler(Exception cause) {
        schedulerNG.suspend(cause);

        if (jobManagerJobMetricGroup != null) {
            jobManagerJobMetricGroup.close();
        }

        if (jobStatusListener != null) {
            jobStatusListener.stop();
        }
    }

    private void clearSchedulerFields() {
        jobManagerJobMetricGroup = null;
        jobStatusListener = null;
    }

    // ----------------------------------------------------------------------------------------------

    private void handleJobMasterError(final Throwable cause) {
        if (ExceptionUtils.isJvmFatalError(cause)) {
            log.error("Fatal error occurred on JobManager.", cause);
            // The fatal error handler implementation should make sure that this call is
            // non-blocking
            fatalErrorHandler.onFatalError(cause);
        } else {
            jobCompletionActions.jobMasterFailed(cause);
        }
    }

    private void jobStatusChanged(
            final JobStatus newJobStatus, long timestamp, @Nullable final Throwable error) {
        validateRunsInMainThread();

        if (newJobStatus.isGloballyTerminalState()) {
            runAsync(
                    () ->
                            registeredTaskManagers
                                    .keySet()
                                    .forEach(
                                            newJobStatus == JobStatus.FINISHED
                                                    ? partitionTracker
                                                            ::stopTrackingAndReleaseOrPromotePartitionsFor
                                                    : partitionTracker
                                                            ::stopTrackingAndReleasePartitionsFor));

            final ArchivedExecutionGraph archivedExecutionGraph = schedulerNG.requestJob();
            scheduledExecutorService.execute(
                    () ->
                            jobCompletionActions.jobReachedGloballyTerminalState(
                                    archivedExecutionGraph));
        }
    }

    private void notifyOfNewResourceManagerLeader(
            final String newResourceManagerAddress, final ResourceManagerId resourceManagerId) {
        resourceManagerAddress =
                createResourceManagerAddress(newResourceManagerAddress, resourceManagerId);

        reconnectToResourceManager(
                new FlinkException(
                        String.format(
                                "ResourceManager leader changed to new address %s",
                                resourceManagerAddress)));
    }

    @Nullable
    private ResourceManagerAddress createResourceManagerAddress(
            @Nullable String newResourceManagerAddress,
            @Nullable ResourceManagerId resourceManagerId) {
        if (newResourceManagerAddress != null) {
            // the contract is: address == null <=> id == null
            checkNotNull(resourceManagerId);
            return new ResourceManagerAddress(newResourceManagerAddress, resourceManagerId);
        } else {
            return null;
        }
    }

    private void reconnectToResourceManager(Exception cause) {
        closeResourceManagerConnection(cause);
        tryConnectToResourceManager();
    }

    private void tryConnectToResourceManager() {
        if (resourceManagerAddress != null) {
            connectToResourceManager();
        }
    }

    private void connectToResourceManager() {
        assert (resourceManagerAddress != null);
        assert (resourceManagerConnection == null);
        assert (establishedResourceManagerConnection == null);

        log.info("Connecting to ResourceManager {}", resourceManagerAddress);

        resourceManagerConnection =
                new ResourceManagerConnection(
                        log,
                        jobGraph.getJobID(),
                        resourceId,
                        getAddress(),
                        getFencingToken(),
                        resourceManagerAddress.getAddress(),
                        resourceManagerAddress.getResourceManagerId(),
                        scheduledExecutorService);

        resourceManagerConnection.start();
    }

    private void establishResourceManagerConnection(final JobMasterRegistrationSuccess success) {
        final ResourceManagerId resourceManagerId = success.getResourceManagerId();

        // verify the response with current connection
        if (resourceManagerConnection != null
                && Objects.equals(
                        resourceManagerConnection.getTargetLeaderId(), resourceManagerId)) {

            log.info(
                    "JobManager successfully registered at ResourceManager, leader id: {}.",
                    resourceManagerId);

            final ResourceManagerGateway resourceManagerGateway =
                    resourceManagerConnection.getTargetGateway();

            final ResourceID resourceManagerResourceId = success.getResourceManagerResourceId();

            establishedResourceManagerConnection =
                    new EstablishedResourceManagerConnection(
                            resourceManagerGateway, resourceManagerResourceId);

            slotPool.connectToResourceManager(resourceManagerGateway);

            resourceManagerHeartbeatManager.monitorTarget(
                    resourceManagerResourceId,
                    new HeartbeatTarget<Void>() {
                        @Override
                        public void receiveHeartbeat(ResourceID resourceID, Void payload) {
                            resourceManagerGateway.heartbeatFromJobManager(resourceID);
                        }

                        @Override
                        public void requestHeartbeat(ResourceID resourceID, Void payload) {
                            // request heartbeat will never be called on the job manager side
                        }
                    });
        } else {
            log.debug(
                    "Ignoring resource manager connection to {} because it's duplicated or outdated.",
                    resourceManagerId);
        }
    }

    private void closeResourceManagerConnection(Exception cause) {
        if (establishedResourceManagerConnection != null) {
            dissolveResourceManagerConnection(establishedResourceManagerConnection, cause);
            establishedResourceManagerConnection = null;
        }

        if (resourceManagerConnection != null) {
            // stop a potentially ongoing registration process
            resourceManagerConnection.close();
            resourceManagerConnection = null;
        }
    }

    private void dissolveResourceManagerConnection(
            EstablishedResourceManagerConnection establishedResourceManagerConnection,
            Exception cause) {
        final ResourceID resourceManagerResourceID =
                establishedResourceManagerConnection.getResourceManagerResourceID();

        if (log.isDebugEnabled()) {
            log.debug("Close ResourceManager connection {}.", resourceManagerResourceID, cause);
        } else {
            log.info(
                    "Close ResourceManager connection {}: {}.",
                    resourceManagerResourceID,
                    cause.getMessage());
        }

        resourceManagerHeartbeatManager.unmonitorTarget(resourceManagerResourceID);

        ResourceManagerGateway resourceManagerGateway =
                establishedResourceManagerConnection.getResourceManagerGateway();
        resourceManagerGateway.disconnectJobManager(jobGraph.getJobID(), cause);
        slotPool.disconnectResourceManager();
    }

    // ----------------------------------------------------------------------------------------------
    // Service methods
    // ----------------------------------------------------------------------------------------------

    @Override
    public JobMasterGateway getGateway() {
        return getSelfGateway(JobMasterGateway.class);
    }

    // ----------------------------------------------------------------------------------------------
    // Utility classes
    // ----------------------------------------------------------------------------------------------

    private class ResourceManagerLeaderListener implements LeaderRetrievalListener {

        @Override
        public void notifyLeaderAddress(final String leaderAddress, final UUID leaderSessionID) {
            runAsync(
                    () ->
                            notifyOfNewResourceManagerLeader(
                                    leaderAddress,
                                    ResourceManagerId.fromUuidOrNull(leaderSessionID)));
        }

        @Override
        public void handleError(final Exception exception) {
            handleJobMasterError(
                    new Exception("Fatal error in the ResourceManager leader service", exception));
        }
    }

    // ----------------------------------------------------------------------------------------------

    private class ResourceManagerConnection
            extends RegisteredRpcConnection<
                    ResourceManagerId,
                    ResourceManagerGateway,
                    JobMasterRegistrationSuccess,
                    RegistrationResponse.Rejection> {
        private final JobID jobID;

        private final ResourceID jobManagerResourceID;

        private final String jobManagerRpcAddress;

        private final JobMasterId jobMasterId;

        ResourceManagerConnection(
                final Logger log,
                final JobID jobID,
                final ResourceID jobManagerResourceID,
                final String jobManagerRpcAddress,
                final JobMasterId jobMasterId,
                final String resourceManagerAddress,
                final ResourceManagerId resourceManagerId,
                final Executor executor) {
            super(log, resourceManagerAddress, resourceManagerId, executor);
            this.jobID = checkNotNull(jobID);
            this.jobManagerResourceID = checkNotNull(jobManagerResourceID);
            this.jobManagerRpcAddress = checkNotNull(jobManagerRpcAddress);
            this.jobMasterId = checkNotNull(jobMasterId);
        }

        @Override
        protected RetryingRegistration<
                        ResourceManagerId,
                        ResourceManagerGateway,
                        JobMasterRegistrationSuccess,
                        RegistrationResponse.Rejection>
                generateRegistration() {
            return new RetryingRegistration<
                    ResourceManagerId,
                    ResourceManagerGateway,
                    JobMasterRegistrationSuccess,
                    RegistrationResponse.Rejection>(
                    log,
                    getRpcService(),
                    "ResourceManager",
                    ResourceManagerGateway.class,
                    getTargetAddress(),
                    getTargetLeaderId(),
                    jobMasterConfiguration.getRetryingRegistrationConfiguration()) {

                @Override
                protected CompletableFuture<RegistrationResponse> invokeRegistration(
                        ResourceManagerGateway gateway,
                        ResourceManagerId fencingToken,
                        long timeoutMillis) {
                    Time timeout = Time.milliseconds(timeoutMillis);

                    return gateway.registerJobManager(
                            jobMasterId,
                            jobManagerResourceID,
                            jobManagerRpcAddress,
                            jobID,
                            timeout);
                }
            };
        }

        @Override
        protected void onRegistrationSuccess(final JobMasterRegistrationSuccess success) {
            runAsync(
                    () -> {
                        // filter out outdated connections
                        //noinspection ObjectEquality
                        if (this == resourceManagerConnection) {
                            establishResourceManagerConnection(success);
                        }
                    });
        }

        @Override
        protected void onRegistrationRejection(RegistrationResponse.Rejection rejection) {
            handleJobMasterError(
                    new IllegalStateException(
                            "The ResourceManager should never reject a JobMaster registration."));
        }

        @Override
        protected void onRegistrationFailure(final Throwable failure) {
            handleJobMasterError(failure);
        }
    }

    // ----------------------------------------------------------------------------------------------

    private class JobManagerJobStatusListener implements JobStatusListener {

        private volatile boolean running = true;

        @Override
        public void jobStatusChanges(
                final JobID jobId,
                final JobStatus newJobStatus,
                final long timestamp,
                final Throwable error) {

            if (running) {
                // run in rpc thread to avoid concurrency
                runAsync(() -> jobStatusChanged(newJobStatus, timestamp, error));
            }
        }

        private void stop() {
            running = false;
        }
    }

    private class TaskManagerHeartbeatListener
            implements HeartbeatListener<AccumulatorReport, AllocatedSlotReport> {

        /**
         * TODO: 连接超时
         * @param resourceID Resource ID of the machine whose heartbeat has timed out
         */
        @Override
        public void notifyHeartbeatTimeout(ResourceID resourceID) {
            validateRunsInMainThread();
            disconnectTaskManager(
                    resourceID,
                    new TimeoutException(
                            "Heartbeat of TaskManager with id " + resourceID + " timed out."));
        }

        @Override
        public void reportPayload(ResourceID resourceID, AccumulatorReport payload) {
            validateRunsInMainThread();
            for (AccumulatorSnapshot snapshot : payload.getAccumulatorSnapshots()) {
                schedulerNG.updateAccumulators(snapshot);
            }
        }

        @Override
        public AllocatedSlotReport retrievePayload(ResourceID resourceID) {
            validateRunsInMainThread();
            return slotPool.createAllocatedSlotReport(resourceID);
        }
    }

    private class ResourceManagerHeartbeatListener implements HeartbeatListener<Void, Void> {

        @Override
        public void notifyHeartbeatTimeout(final ResourceID resourceId) {
            validateRunsInMainThread();
            log.info("The heartbeat of ResourceManager with id {} timed out.", resourceId);

            if (establishedResourceManagerConnection != null
                    && establishedResourceManagerConnection
                            .getResourceManagerResourceID()
                            .equals(resourceId)) {
                reconnectToResourceManager(
                        new JobMasterException(
                                String.format(
                                        "The heartbeat of ResourceManager with id %s timed out.",
                                        resourceId)));
            }
        }

        @Override
        public void reportPayload(ResourceID resourceID, Void payload) {
            // nothing to do since the payload is of type Void
        }

        @Override
        public Void retrievePayload(ResourceID resourceID) {
            return null;
        }
    }
}
