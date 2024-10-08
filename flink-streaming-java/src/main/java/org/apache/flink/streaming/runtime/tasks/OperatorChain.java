/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.runtime.event.AbstractEvent;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.io.network.api.writer.RecordWriter;
import org.apache.flink.runtime.io.network.api.writer.RecordWriterDelegate;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.metrics.MetricNames;
import org.apache.flink.runtime.metrics.groups.OperatorIOMetricGroup;
import org.apache.flink.runtime.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.operators.coordination.OperatorEvent;
import org.apache.flink.runtime.operators.coordination.OperatorEventDispatcher;
import org.apache.flink.runtime.plugable.SerializationDelegate;
import org.apache.flink.streaming.api.collector.selector.CopyingDirectedOutput;
import org.apache.flink.streaming.api.collector.selector.DirectedOutput;
import org.apache.flink.streaming.api.collector.selector.OutputSelector;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.graph.StreamEdge;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperatorFactoryUtil;
import org.apache.flink.streaming.api.operators.StreamTaskStateInitializer;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.RecordWriterOutput;
import org.apache.flink.streaming.runtime.metrics.WatermarkGauge;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatus;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusMaintainer;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusProvider;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxExecutorFactory;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.SerializedValue;
import org.apache.flink.util.XORShiftRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * The {@code OperatorChain} contains all operators that are executed as one chain within a single
 * {@link StreamTask}.
 *
 * @param <OUT> The type of elements accepted by the chain, i.e., the input type of the chain's head
 *     operator.
 */
@Internal
public class OperatorChain<OUT, OP extends StreamOperator<OUT>> implements StreamStatusMaintainer {

    private static final Logger LOG = LoggerFactory.getLogger(OperatorChain.class);

    private final RecordWriterOutput<?>[] streamOutputs;

    private final WatermarkGaugeExposingOutput<StreamRecord<OUT>> chainEntryPoint;

    /**
     * For iteration, {@link StreamIterationHead} and {@link StreamIterationTail} used for executing
     * feedback edges do not contain any operators, in which case, {@code headOperatorWrapper} and
     * {@code tailOperatorWrapper} are null.
     */
    @Nullable private final StreamOperatorWrapper<OUT, OP> headOperatorWrapper;

    @Nullable private final StreamOperatorWrapper<?, ?> tailOperatorWrapper;

    private final int numOperators;

    private final OperatorEventDispatcherImpl operatorEventDispatcher;

    private boolean ignoreEndOfInput;

    /**
     * Current status of the input stream of the operator chain. Watermarks explicitly generated by
     * operators in the chain (i.e. timestamp assigner / watermark extractors), will be blocked and
     * not forwarded if this value is {@link StreamStatus#IDLE}.
     */
    private StreamStatus streamStatus = StreamStatus.ACTIVE;

    public OperatorChain(
            StreamTask<OUT, OP> containingTask,
            RecordWriterDelegate<SerializationDelegate<StreamRecord<OUT>>> recordWriterDelegate) {

        this.operatorEventDispatcher =
                new OperatorEventDispatcherImpl(
                        containingTask.getEnvironment().getUserClassLoader(),
                        containingTask.getEnvironment().getOperatorCoordinatorEventGateway());

        final ClassLoader userCodeClassloader = containingTask.getUserCodeClassLoader();
        final StreamConfig configuration = containingTask.getConfiguration();

        StreamOperatorFactory<OUT> operatorFactory =
                configuration.getStreamOperatorFactory(userCodeClassloader);

        // we read the chained configs, and the order of record writer registrations by output name
        Map<Integer, StreamConfig> chainedConfigs =
                configuration.getTransitiveChainedTaskConfigsWithSelf(userCodeClassloader);

        // create the final output stream writers
        // we iterate through all the out edges from this job vertex and create a stream output
        List<StreamEdge> outEdgesInOrder = configuration.getOutEdgesInOrder(userCodeClassloader);
        Map<StreamEdge, RecordWriterOutput<?>> streamOutputMap =
                new HashMap<>(outEdgesInOrder.size());
        this.streamOutputs = new RecordWriterOutput<?>[outEdgesInOrder.size()];

        // from here on, we need to make sure that the output writers are shut down again on failure
        boolean success = false;
        try {
            for (int i = 0; i < outEdgesInOrder.size(); i++) {
                StreamEdge outEdge = outEdgesInOrder.get(i);

                RecordWriterOutput<?> streamOutput =
                        createStreamOutput(
                                recordWriterDelegate.getRecordWriter(i),
                                outEdge,
                                chainedConfigs.get(outEdge.getSourceId()),
                                containingTask.getEnvironment());

                this.streamOutputs[i] = streamOutput;
                streamOutputMap.put(outEdge, streamOutput);
            }

            // we create the chain of operators and grab the collector that leads into the chain
            List<StreamOperatorWrapper<?, ?>> allOpWrappers =
                    new ArrayList<>(chainedConfigs.size());
            this.chainEntryPoint =
                    createOutputCollector(
                            containingTask,
                            configuration,
                            chainedConfigs,
                            userCodeClassloader,
                            streamOutputMap,
                            allOpWrappers,
                            containingTask.getMailboxExecutorFactory());

            if (operatorFactory != null) {
                WatermarkGaugeExposingOutput<StreamRecord<OUT>> output = getChainEntryPoint();

                Tuple2<OP, Optional<ProcessingTimeService>> headOperatorAndTimeService =
                        StreamOperatorFactoryUtil.createOperator(
                                operatorFactory,
                                containingTask,
                                configuration,
                                output,
                                operatorEventDispatcher);

                OP headOperator = headOperatorAndTimeService.f0;
                headOperator
                        .getMetricGroup()
                        .gauge(MetricNames.IO_CURRENT_OUTPUT_WATERMARK, output.getWatermarkGauge());
                this.headOperatorWrapper =
                        createOperatorWrapper(
                                headOperator,
                                containingTask,
                                configuration,
                                headOperatorAndTimeService.f1);

                // add head operator to end of chain
                allOpWrappers.add(headOperatorWrapper);

                this.tailOperatorWrapper = allOpWrappers.get(0);
            } else {
                checkState(allOpWrappers.size() == 0);
                this.headOperatorWrapper = null;
                this.tailOperatorWrapper = null;
            }

            this.numOperators = allOpWrappers.size();

            linkOperatorWrappers(allOpWrappers);

            success = true;
        } finally {
            // make sure we clean up after ourselves in case of a failure after acquiring
            // the first resources
            if (!success) {
                for (RecordWriterOutput<?> output : this.streamOutputs) {
                    if (output != null) {
                        output.close();
                    }
                }
            }
        }
    }

    @VisibleForTesting
    OperatorChain(
            List<StreamOperatorWrapper<?, ?>> allOperatorWrappers,
            RecordWriterOutput<?>[] streamOutputs,
            WatermarkGaugeExposingOutput<StreamRecord<OUT>> chainEntryPoint,
            StreamOperatorWrapper<OUT, OP> headOperatorWrapper) {

        this.streamOutputs = checkNotNull(streamOutputs);
        this.chainEntryPoint = checkNotNull(chainEntryPoint);
        this.operatorEventDispatcher = null;

        checkState(allOperatorWrappers != null && allOperatorWrappers.size() > 0);
        this.headOperatorWrapper = checkNotNull(headOperatorWrapper);
        this.tailOperatorWrapper = allOperatorWrappers.get(0);
        this.numOperators = allOperatorWrappers.size();

        linkOperatorWrappers(allOperatorWrappers);
    }

    @Override
    public StreamStatus getStreamStatus() {
        return streamStatus;
    }

    public OperatorEventDispatcher getOperatorEventDispatcher() {
        return operatorEventDispatcher;
    }

    public void dispatchOperatorEvent(OperatorID operator, SerializedValue<OperatorEvent> event)
            throws FlinkException {
        operatorEventDispatcher.dispatchEventToHandlers(operator, event);
    }

    @Override
    public void toggleStreamStatus(StreamStatus status) {
        if (!status.equals(this.streamStatus)) {
            this.streamStatus = status;

            // try and forward the stream status change to all outgoing connections
            for (RecordWriterOutput<?> streamOutput : streamOutputs) {
                streamOutput.emitStreamStatus(status);
            }
        }
    }

    public void broadcastEvent(AbstractEvent event) throws IOException {
        broadcastEvent(event, false);
    }

    public void broadcastEvent(AbstractEvent event, boolean isPriorityEvent) throws IOException {
        for (RecordWriterOutput<?> streamOutput : streamOutputs) {
            streamOutput.broadcastEvent(event, isPriorityEvent);
        }
    }

    public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
        // go forward through the operator chain and tell each operator
        // to prepare the checkpoint
        for (StreamOperatorWrapper<?, ?> operatorWrapper : getAllOperators()) {
            if (!operatorWrapper.isClosed()) {
                operatorWrapper.getStreamOperator().prepareSnapshotPreBarrier(checkpointId);
            }
        }
    }

    /**
     * Ends the head operator input specified by {@code inputId}).
     *
     * @param inputId the input ID starts from 1 which indicates the first input.
     */
    public void endHeadOperatorInput(int inputId) throws Exception {
        if (headOperatorWrapper != null && !ignoreEndOfInput) {
            headOperatorWrapper.endOperatorInput(inputId);
        }
    }

    /**
     * Initialize state and open all operators in the chain from <b>tail to head</b>, contrary to
     * {@link StreamOperator#close()} which happens <b>head to tail</b> (see {@link
     * #closeOperators(StreamTaskActionExecutor)}).
     */
    protected void initializeStateAndOpenOperators(
            StreamTaskStateInitializer streamTaskStateInitializer) throws Exception {
        for (StreamOperatorWrapper<?, ?> operatorWrapper : getAllOperators(true)) {
            StreamOperator<?> operator = operatorWrapper.getStreamOperator();
            // clouding 注释: 2022/4/16 21:45
            //          初始化 state
            operator.initializeState(streamTaskStateInitializer);
            // clouding 注释: 2022/4/16 21:46
            //          开启算子,加载udf
            operator.open();
        }
    }

    /**
     * Closes all operators in a chain effect way. Closing happens from <b>head to tail</b> operator
     * in the chain, contrary to {@link StreamOperator#open()} which happens <b>tail to head</b>
     * (see {@link #initializeStateAndOpenOperators(StreamTaskStateInitializer)}).
     */
    protected void closeOperators(StreamTaskActionExecutor actionExecutor) throws Exception {
        if (headOperatorWrapper != null) {
            headOperatorWrapper.close(actionExecutor, ignoreEndOfInput);
        }
    }

    public RecordWriterOutput<?>[] getStreamOutputs() {
        return streamOutputs;
    }

    /** Returns an {@link Iterable} which traverses all operators in forward topological order. */
    public Iterable<StreamOperatorWrapper<?, ?>> getAllOperators() {
        return getAllOperators(false);
    }

    /**
     * Returns an {@link Iterable} which traverses all operators in forward or reverse topological
     * order.
     */
    public Iterable<StreamOperatorWrapper<?, ?>> getAllOperators(boolean reverse) {
        return reverse
                ? new StreamOperatorWrapper.ReadIterator(tailOperatorWrapper, true)
                : new StreamOperatorWrapper.ReadIterator(headOperatorWrapper, false);
    }

    public int getNumberOfOperators() {
        return numOperators;
    }

    public WatermarkGaugeExposingOutput<StreamRecord<OUT>> getChainEntryPoint() {
        return chainEntryPoint;
    }

    /**
     * This method should be called before finishing the record emission, to make sure any data that
     * is still buffered will be sent. It also ensures that all data sending related exceptions are
     * recognized.
     *
     * @throws IOException Thrown, if the buffered data cannot be pushed into the output streams.
     */
    public void flushOutputs() throws IOException {
        for (RecordWriterOutput<?> streamOutput : getStreamOutputs()) {
            streamOutput.flush();
        }
    }

    /**
     * This method releases all resources of the record writer output. It stops the output flushing
     * thread (if there is one) and releases all buffers currently held by the output serializers.
     *
     * <p>This method should never fail.
     */
    public void releaseOutputs() {
        for (RecordWriterOutput<?> streamOutput : streamOutputs) {
            streamOutput.close();
        }
    }

    @Nullable
    public OP getHeadOperator() {
        return (headOperatorWrapper == null) ? null : headOperatorWrapper.getStreamOperator();
    }

    // ------------------------------------------------------------------------
    //  initialization utilities
    // ------------------------------------------------------------------------

    private <T> WatermarkGaugeExposingOutput<StreamRecord<T>> createOutputCollector(
            StreamTask<?, ?> containingTask,
            StreamConfig operatorConfig,
            Map<Integer, StreamConfig> chainedConfigs,
            ClassLoader userCodeClassloader,
            Map<StreamEdge, RecordWriterOutput<?>> streamOutputs,
            List<StreamOperatorWrapper<?, ?>> allOperatorWrappers,
            MailboxExecutorFactory mailboxExecutorFactory) {
        List<Tuple2<WatermarkGaugeExposingOutput<StreamRecord<T>>, StreamEdge>> allOutputs =
                new ArrayList<>(4);

        // create collectors for the network outputs
        for (StreamEdge outputEdge : operatorConfig.getNonChainedOutputs(userCodeClassloader)) {
            @SuppressWarnings("unchecked")
            RecordWriterOutput<T> output = (RecordWriterOutput<T>) streamOutputs.get(outputEdge);

            allOutputs.add(new Tuple2<>(output, outputEdge));
        }

        // Create collectors for the chained outputs
        for (StreamEdge outputEdge : operatorConfig.getChainedOutputs(userCodeClassloader)) {
            int outputId = outputEdge.getTargetId();
            StreamConfig chainedOpConfig = chainedConfigs.get(outputId);

            WatermarkGaugeExposingOutput<StreamRecord<T>> output =
                    createChainedOperator(
                            containingTask,
                            chainedOpConfig,
                            chainedConfigs,
                            userCodeClassloader,
                            streamOutputs,
                            allOperatorWrappers,
                            outputEdge.getOutputTag(),
                            mailboxExecutorFactory);
            allOutputs.add(new Tuple2<>(output, outputEdge));
        }

        // if there are multiple outputs, or the outputs are directed, we need to
        // wrap them as one output

        List<OutputSelector<T>> selectors = operatorConfig.getOutputSelectors(userCodeClassloader);

        if (selectors == null || selectors.isEmpty()) {
            // simple path, no selector necessary
            if (allOutputs.size() == 1) {
                return allOutputs.get(0).f0;
            } else {
                // send to N outputs. Note that this includes the special case
                // of sending to zero outputs
                @SuppressWarnings({"unchecked", "rawtypes"})
                Output<StreamRecord<T>>[] asArray = new Output[allOutputs.size()];
                for (int i = 0; i < allOutputs.size(); i++) {
                    asArray[i] = allOutputs.get(i).f0;
                }

                // This is the inverse of creating the normal ChainingOutput.
                // If the chaining output does not copy we need to copy in the broadcast output,
                // otherwise multi-chaining would not work correctly.
                if (containingTask.getExecutionConfig().isObjectReuseEnabled()) {
                    return new CopyingBroadcastingOutputCollector<>(asArray, this);
                } else {
                    return new BroadcastingOutputCollector<>(asArray, this);
                }
            }
        } else {
            // selector present, more complex routing necessary

            // This is the inverse of creating the normal ChainingOutput.
            // If the chaining output does not copy we need to copy in the broadcast output,
            // otherwise multi-chaining would not work correctly.
            if (containingTask.getExecutionConfig().isObjectReuseEnabled()) {
                return new CopyingDirectedOutput<>(selectors, allOutputs);
            } else {
                return new DirectedOutput<>(selectors, allOutputs);
            }
        }
    }

    private <IN, OUT> WatermarkGaugeExposingOutput<StreamRecord<IN>> createChainedOperator(
            StreamTask<OUT, ?> containingTask,
            StreamConfig operatorConfig,
            Map<Integer, StreamConfig> chainedConfigs,
            ClassLoader userCodeClassloader,
            Map<StreamEdge, RecordWriterOutput<?>> streamOutputs,
            List<StreamOperatorWrapper<?, ?>> allOperatorWrappers,
            OutputTag<IN> outputTag,
            MailboxExecutorFactory mailboxExecutorFactory) {
        // create the output that the operator writes to first. this may recursively create more
        // operators
        WatermarkGaugeExposingOutput<StreamRecord<OUT>> chainedOperatorOutput =
                createOutputCollector(
                        containingTask,
                        operatorConfig,
                        chainedConfigs,
                        userCodeClassloader,
                        streamOutputs,
                        allOperatorWrappers,
                        mailboxExecutorFactory);

        // now create the operator and give it the output collector to write its output to
        Tuple2<OneInputStreamOperator<IN, OUT>, Optional<ProcessingTimeService>>
                chainedOperatorAndTimeService =
                        StreamOperatorFactoryUtil.createOperator(
                                operatorConfig.getStreamOperatorFactory(userCodeClassloader),
                                containingTask,
                                operatorConfig,
                                chainedOperatorOutput,
                                operatorEventDispatcher);

        OneInputStreamOperator<IN, OUT> chainedOperator = chainedOperatorAndTimeService.f0;
        allOperatorWrappers.add(
                createOperatorWrapper(
                        chainedOperator,
                        containingTask,
                        operatorConfig,
                        chainedOperatorAndTimeService.f1));

        WatermarkGaugeExposingOutput<StreamRecord<IN>> currentOperatorOutput;
        if (containingTask.getExecutionConfig().isObjectReuseEnabled()) {
            currentOperatorOutput = new ChainingOutput<>(chainedOperator, this, outputTag);
        } else {
            TypeSerializer<IN> inSerializer =
                    operatorConfig.getTypeSerializerIn1(userCodeClassloader);
            currentOperatorOutput =
                    new CopyingChainingOutput<>(chainedOperator, inSerializer, outputTag, this);
        }

        // wrap watermark gauges since registered metrics must be unique
        chainedOperator
                .getMetricGroup()
                .gauge(
                        MetricNames.IO_CURRENT_INPUT_WATERMARK,
                        currentOperatorOutput.getWatermarkGauge()::getValue);
        chainedOperator
                .getMetricGroup()
                .gauge(
                        MetricNames.IO_CURRENT_OUTPUT_WATERMARK,
                        chainedOperatorOutput.getWatermarkGauge()::getValue);

        return currentOperatorOutput;
    }

    private RecordWriterOutput<OUT> createStreamOutput(
            RecordWriter<SerializationDelegate<StreamRecord<OUT>>> recordWriter,
            StreamEdge edge,
            StreamConfig upStreamConfig,
            Environment taskEnvironment) {
        OutputTag sideOutputTag = edge.getOutputTag(); // OutputTag, return null if not sideOutput

        TypeSerializer outSerializer = null;

        if (edge.getOutputTag() != null) {
            // side output
            outSerializer =
                    upStreamConfig.getTypeSerializerSideOut(
                            edge.getOutputTag(), taskEnvironment.getUserClassLoader());
        } else {
            // main output
            outSerializer =
                    upStreamConfig.getTypeSerializerOut(taskEnvironment.getUserClassLoader());
        }

        return new RecordWriterOutput<>(
                recordWriter,
                outSerializer,
                sideOutputTag,
                this,
                edge.supportsUnalignedCheckpoints());
    }

    /**
     * Links operator wrappers in forward topological order.
     *
     * @param allOperatorWrappers is an operator wrapper list of reverse topological order
     */
    private void linkOperatorWrappers(List<StreamOperatorWrapper<?, ?>> allOperatorWrappers) {
        StreamOperatorWrapper<?, ?> previous = null;
        for (StreamOperatorWrapper<?, ?> current : allOperatorWrappers) {
            if (previous != null) {
                previous.setPrevious(current);
            }
            current.setNext(previous);
            previous = current;
        }
    }

    private <T, P extends StreamOperator<T>> StreamOperatorWrapper<T, P> createOperatorWrapper(
            P operator,
            StreamTask<?, ?> containingTask,
            StreamConfig operatorConfig,
            Optional<ProcessingTimeService> processingTimeService) {

        return new StreamOperatorWrapper<>(
                operator,
                processingTimeService,
                containingTask
                        .getMailboxExecutorFactory()
                        .createExecutor(operatorConfig.getChainIndex()));
    }

    @Nullable
    StreamOperator<?> getTailOperator() {
        return (tailOperatorWrapper == null) ? null : tailOperatorWrapper.getStreamOperator();
    }

    public void setIgnoreEndOfInput(boolean ignoreEndOfInput) {
        this.ignoreEndOfInput = ignoreEndOfInput;
    }

    // ------------------------------------------------------------------------
    //  Collectors for output chaining
    // ------------------------------------------------------------------------

    /**
     * An {@link Output} that measures the last emitted watermark with a {@link WatermarkGauge}.
     *
     * @param <T> The type of the elements that can be emitted.
     */
    public interface WatermarkGaugeExposingOutput<T> extends Output<T> {
        Gauge<Long> getWatermarkGauge();
    }

    static class ChainingOutput<T> implements WatermarkGaugeExposingOutput<StreamRecord<T>> {

        protected final OneInputStreamOperator<T, ?> operator;
        protected final Counter numRecordsIn;
        protected final WatermarkGauge watermarkGauge = new WatermarkGauge();

        protected final StreamStatusProvider streamStatusProvider;

        @Nullable protected final OutputTag<T> outputTag;

        public ChainingOutput(
                OneInputStreamOperator<T, ?> operator,
                StreamStatusProvider streamStatusProvider,
                @Nullable OutputTag<T> outputTag) {
            this.operator = operator;

            {
                Counter tmpNumRecordsIn;
                try {
                    OperatorIOMetricGroup ioMetricGroup =
                            ((OperatorMetricGroup) operator.getMetricGroup()).getIOMetricGroup();
                    tmpNumRecordsIn = ioMetricGroup.getNumRecordsInCounter();
                } catch (Exception e) {
                    LOG.warn("An exception occurred during the metrics setup.", e);
                    tmpNumRecordsIn = new SimpleCounter();
                }
                numRecordsIn = tmpNumRecordsIn;
            }

            this.streamStatusProvider = streamStatusProvider;
            this.outputTag = outputTag;
        }

        @Override
        public void collect(StreamRecord<T> record) {
            if (this.outputTag != null) {
                // we are not responsible for emitting to the main output.
                return;
            }

            pushToOperator(record);
        }

        @Override
        public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
            if (this.outputTag == null || !this.outputTag.equals(outputTag)) {
                // we are not responsible for emitting to the side-output specified by this
                // OutputTag.
                return;
            }

            pushToOperator(record);
        }

        protected <X> void pushToOperator(StreamRecord<X> record) {
            try {
                // we know that the given outputTag matches our OutputTag so the record
                // must be of the type that our operator expects.
                @SuppressWarnings("unchecked")
                StreamRecord<T> castRecord = (StreamRecord<T>) record;

                numRecordsIn.inc();
                operator.setKeyContextElement1(castRecord);
                operator.processElement(castRecord);
            } catch (Exception e) {
                throw new ExceptionInChainedOperatorException(e);
            }
        }

        @Override
        public void emitWatermark(Watermark mark) {
            try {
                watermarkGauge.setCurrentWatermark(mark.getTimestamp());
                if (streamStatusProvider.getStreamStatus().isActive()) {
                    operator.processWatermark(mark);
                }
            } catch (Exception e) {
                throw new ExceptionInChainedOperatorException(e);
            }
        }

        @Override
        public void emitLatencyMarker(LatencyMarker latencyMarker) {
            try {
                operator.processLatencyMarker(latencyMarker);
            } catch (Exception e) {
                throw new ExceptionInChainedOperatorException(e);
            }
        }

        @Override
        public void close() {
            try {
                operator.close();
            } catch (Exception e) {
                throw new ExceptionInChainedOperatorException(e);
            }
        }

        @Override
        public Gauge<Long> getWatermarkGauge() {
            return watermarkGauge;
        }
    }

    static final class CopyingChainingOutput<T> extends ChainingOutput<T> {

        private final TypeSerializer<T> serializer;

        public CopyingChainingOutput(
                OneInputStreamOperator<T, ?> operator,
                TypeSerializer<T> serializer,
                OutputTag<T> outputTag,
                StreamStatusProvider streamStatusProvider) {
            super(operator, streamStatusProvider, outputTag);
            this.serializer = serializer;
        }

        @Override
        public void collect(StreamRecord<T> record) {
            if (this.outputTag != null) {
                // we are not responsible for emitting to the main output.
                return;
            }

            pushToOperator(record);
        }

        @Override
        public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
            if (this.outputTag == null || !this.outputTag.equals(outputTag)) {
                // we are not responsible for emitting to the side-output specified by this
                // OutputTag.
                return;
            }

            pushToOperator(record);
        }

        @Override
        protected <X> void pushToOperator(StreamRecord<X> record) {
            try {
                // we know that the given outputTag matches our OutputTag so the record
                // must be of the type that our operator (and Serializer) expects.
                @SuppressWarnings("unchecked")
                StreamRecord<T> castRecord = (StreamRecord<T>) record;

                numRecordsIn.inc();
                StreamRecord<T> copy = castRecord.copy(serializer.copy(castRecord.getValue()));
                operator.setKeyContextElement1(copy);
                operator.processElement(copy);
            } catch (ClassCastException e) {
                if (outputTag != null) {
                    // Enrich error message
                    ClassCastException replace =
                            new ClassCastException(
                                    String.format(
                                            "%s. Failed to push OutputTag with id '%s' to operator. "
                                                    + "This can occur when multiple OutputTags with different types "
                                                    + "but identical names are being used.",
                                            e.getMessage(), outputTag.getId()));

                    throw new ExceptionInChainedOperatorException(replace);
                } else {
                    throw new ExceptionInChainedOperatorException(e);
                }
            } catch (Exception e) {
                throw new ExceptionInChainedOperatorException(e);
            }
        }
    }

    static class BroadcastingOutputCollector<T>
            implements WatermarkGaugeExposingOutput<StreamRecord<T>> {

        protected final Output<StreamRecord<T>>[] outputs;

        private final Random random = new XORShiftRandom();

        private final StreamStatusProvider streamStatusProvider;

        private final WatermarkGauge watermarkGauge = new WatermarkGauge();

        public BroadcastingOutputCollector(
                Output<StreamRecord<T>>[] outputs, StreamStatusProvider streamStatusProvider) {
            this.outputs = outputs;
            this.streamStatusProvider = streamStatusProvider;
        }

        @Override
        public void emitWatermark(Watermark mark) {
            watermarkGauge.setCurrentWatermark(mark.getTimestamp());
            if (streamStatusProvider.getStreamStatus().isActive()) {
                for (Output<StreamRecord<T>> output : outputs) {
                    output.emitWatermark(mark);
                }
            }
        }

        @Override
        public void emitLatencyMarker(LatencyMarker latencyMarker) {
            if (outputs.length <= 0) {
                // ignore
            } else if (outputs.length == 1) {
                outputs[0].emitLatencyMarker(latencyMarker);
            } else {
                // randomly select an output
                outputs[random.nextInt(outputs.length)].emitLatencyMarker(latencyMarker);
            }
        }

        @Override
        public Gauge<Long> getWatermarkGauge() {
            return watermarkGauge;
        }

        @Override
        public void collect(StreamRecord<T> record) {
            for (Output<StreamRecord<T>> output : outputs) {
                output.collect(record);
            }
        }

        @Override
        public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
            for (Output<StreamRecord<T>> output : outputs) {
                output.collect(outputTag, record);
            }
        }

        @Override
        public void close() {
            for (Output<StreamRecord<T>> output : outputs) {
                output.close();
            }
        }
    }

    /**
     * Special version of {@link BroadcastingOutputCollector} that performs a shallow copy of the
     * {@link StreamRecord} to ensure that multi-chaining works correctly.
     */
    static final class CopyingBroadcastingOutputCollector<T>
            extends BroadcastingOutputCollector<T> {

        public CopyingBroadcastingOutputCollector(
                Output<StreamRecord<T>>[] outputs, StreamStatusProvider streamStatusProvider) {
            super(outputs, streamStatusProvider);
        }

        @Override
        public void collect(StreamRecord<T> record) {

            for (int i = 0; i < outputs.length - 1; i++) {
                Output<StreamRecord<T>> output = outputs[i];
                StreamRecord<T> shallowCopy = record.copy(record.getValue());
                output.collect(shallowCopy);
            }

            if (outputs.length > 0) {
                // don't copy for the last output
                outputs[outputs.length - 1].collect(record);
            }
        }

        @Override
        public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
            for (int i = 0; i < outputs.length - 1; i++) {
                Output<StreamRecord<T>> output = outputs[i];

                StreamRecord<X> shallowCopy = record.copy(record.getValue());
                output.collect(outputTag, shallowCopy);
            }

            if (outputs.length > 0) {
                // don't copy for the last output
                outputs[outputs.length - 1].collect(outputTag, record);
            }
        }
    }
}
