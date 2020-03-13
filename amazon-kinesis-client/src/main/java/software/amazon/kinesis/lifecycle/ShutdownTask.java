/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the
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
package software.amazon.kinesis.lifecycle;

import com.google.common.annotations.VisibleForTesting;

import com.sun.org.apache.bcel.internal.generic.LUSHR;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.services.kinesis.model.ChildShard;
import software.amazon.awssdk.services.kinesis.model.Shard;
import software.amazon.awssdk.utils.CollectionUtils;
import software.amazon.kinesis.annotations.KinesisClientInternalApi;
import software.amazon.kinesis.checkpoint.ShardRecordProcessorCheckpointer;
import software.amazon.kinesis.common.InitialPositionInStreamExtended;
import software.amazon.kinesis.leases.Lease;
import software.amazon.kinesis.leases.LeaseCoordinator;
import software.amazon.kinesis.leases.LeaseRefresher;
import software.amazon.kinesis.leases.ShardDetector;
import software.amazon.kinesis.leases.ShardInfo;
import software.amazon.kinesis.leases.HierarchicalShardSyncer;
import software.amazon.kinesis.leases.exceptions.DependencyException;
import software.amazon.kinesis.leases.exceptions.InvalidStateException;
import software.amazon.kinesis.leases.exceptions.ProvisionedThroughputException;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.metrics.MetricsFactory;
import software.amazon.kinesis.metrics.MetricsScope;
import software.amazon.kinesis.metrics.MetricsLevel;
import software.amazon.kinesis.metrics.MetricsUtil;
import software.amazon.kinesis.processor.ShardRecordProcessor;
import software.amazon.kinesis.retrieval.RecordsPublisher;
import software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Task for invoking the ShardRecordProcessor shutdown() callback.
 */
@RequiredArgsConstructor
@Slf4j
@KinesisClientInternalApi
public class ShutdownTask implements ConsumerTask {
    private static final String SHUTDOWN_TASK_OPERATION = "ShutdownTask";
    private static final String RECORD_PROCESSOR_SHUTDOWN_METRIC = "RecordProcessor.shutdown";

    @NonNull
    private final ShardInfo shardInfo;
    @NonNull
    private final ShardDetector shardDetector;
    @NonNull
    private final ShardRecordProcessor shardRecordProcessor;
    @NonNull
    private final ShardRecordProcessorCheckpointer recordProcessorCheckpointer;
    @NonNull
    private final ShutdownReason reason;
    @NonNull
    private final InitialPositionInStreamExtended initialPositionInStream;
    private final boolean cleanupLeasesOfCompletedShards;
    private final boolean ignoreUnexpectedChildShards;
    @NonNull
    private final LeaseCoordinator leaseCoordinator;
    private final long backoffTimeMillis;
    @NonNull
    private final RecordsPublisher recordsPublisher;
    @NonNull
    private final HierarchicalShardSyncer hierarchicalShardSyncer;
    @NonNull
    private final MetricsFactory metricsFactory;

    private final TaskType taskType = TaskType.SHUTDOWN;

    private final List<ChildShard> childShards;

    /*
     * Invokes ShardRecordProcessor shutdown() API.
     * (non-Javadoc)
     * 
     * @see com.amazonaws.services.kinesis.clientlibrary.lib.worker.ConsumerTask#call()
     */
    @Override
    public TaskResult call() {
        recordProcessorCheckpointer.checkpointer().operation(SHUTDOWN_TASK_OPERATION);
        final MetricsScope scope = MetricsUtil.createMetricsWithOperation(metricsFactory, SHUTDOWN_TASK_OPERATION);

        Exception exception;
        boolean applicationException = false;

        try {
            try {
                log.debug("Invoking shutdown() for shard {}, concurrencyToken {}. Shutdown reason: {}",
                        shardInfo.shardId(), shardInfo.concurrencyToken(), reason);

                final long startTime = System.currentTimeMillis();
                if (reason == ShutdownReason.SHARD_END) {
                    // Create new lease for the child shards if they don't exist.
                    if (!CollectionUtils.isNullOrEmpty(childShards)) {
                        createLeasesForChildShardsIfNotExist();
                    }

                    recordProcessorCheckpointer
                            .sequenceNumberAtShardEnd(recordProcessorCheckpointer.largestPermittedCheckpointValue());
                    recordProcessorCheckpointer.largestPermittedCheckpointValue(ExtendedSequenceNumber.SHARD_END);
                    // Call the shardReocrdsProcessor to checkpoint with SHARD_END sequence number.
                    // The shardEnded is implemented by customer. We should validate if the Shard_End checkpointing is successful after calling shardEnded.
                    try {
                        shardRecordProcessor.shardEnded(ShardEndedInput.builder().checkpointer(recordProcessorCheckpointer).build());
                        final ExtendedSequenceNumber lastCheckpointValue = recordProcessorCheckpointer.lastCheckpointValue();
                        if (lastCheckpointValue == null
                                || !lastCheckpointValue.equals(ExtendedSequenceNumber.SHARD_END)) {
                            throw new IllegalArgumentException("Application didn't checkpoint at end of shard "
                                                                       + shardInfo.shardId() + ". Application must checkpoint upon shard end. " +
                                                                       "See ShardRecordProcessor.shardEnded javadocs for more information.");
                        }
                    } catch (Exception e) {
                        applicationException = true;
                        throw e;
                    } finally {
                        MetricsUtil.addLatency(scope, RECORD_PROCESSOR_SHUTDOWN_METRIC, startTime, MetricsLevel.SUMMARY);
                    }
                } else {
                    try {
                        shardRecordProcessor.leaseLost(LeaseLostInput.builder().build());
                    } catch (Exception e) {
                        applicationException = true;
                        throw e;
                    }
                }

                log.debug("Shutting down retrieval strategy.");
                recordsPublisher.shutdown();
                log.debug("Record processor completed shutdown() for shard {}", shardInfo.shardId());

                return new TaskResult(null);
            } catch (Exception e) {
                if (applicationException) {
                    log.error("Application exception. ", e);
                } else {
                    log.error("Caught exception: ", e);
                }
                exception = e;
                // backoff if we encounter an exception.
                try {
                    Thread.sleep(this.backoffTimeMillis);
                } catch (InterruptedException ie) {
                    log.debug("Interrupted sleep", ie);
                }
            }
        } finally {
            MetricsUtil.endScope(scope);
        }

        return new TaskResult(exception);
    }

    private void createLeasesForChildShardsIfNotExist()
            throws DependencyException, InvalidStateException, ProvisionedThroughputException {
        for(ChildShard childShard : childShards) {
            if(leaseCoordinator.getCurrentlyHeldLease(shardInfo.shardId()) == null) {
                final Lease leaseToCreate = hierarchicalShardSyncer.createLeaseForChildShard(childShard);
                leaseCoordinator.leaseRefresher().createLeaseIfNotExists(leaseToCreate);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.amazonaws.services.kinesis.clientlibrary.lib.worker.ConsumerTask#taskType()
     */
    @Override
    public TaskType taskType() {
        return taskType;
    }

    @VisibleForTesting
    public ShutdownReason getReason() {
        return reason;
    }

    private boolean isShardInContextParentOfAny(List<Shard> shards) {
        for(Shard shard : shards) {
            if (isChildShardOfShardInContext(shard)) {
                return true;
            }
        }
        return false;
    }

    private boolean isChildShardOfShardInContext(Shard shard) {
        return (StringUtils.equals(shard.parentShardId(), shardInfo.shardId())
                || StringUtils.equals(shard.adjacentParentShardId(), shardInfo.shardId()));
    }

    private void dropLease() {
        Lease currentLease = leaseCoordinator.getCurrentlyHeldLease(shardInfo.shardId());
        leaseCoordinator.dropLease(currentLease);
        if(currentLease != null) {
            log.warn("Dropped lease for shutting down ShardConsumer: " + currentLease.leaseKey());
        }
    }

}
