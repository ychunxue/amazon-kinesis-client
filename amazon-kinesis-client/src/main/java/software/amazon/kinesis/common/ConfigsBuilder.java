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

package software.amazon.kinesis.common;

import org.apache.commons.lang3.StringUtils;

import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.kinesis.checkpoint.CheckpointConfig;
import software.amazon.kinesis.coordinator.CoordinatorConfig;
import software.amazon.kinesis.leases.LeaseManagementConfig;
import software.amazon.kinesis.lifecycle.LifecycleConfig;
import software.amazon.kinesis.metrics.MetricsConfig;
import software.amazon.kinesis.processor.ProcessorConfig;
import software.amazon.kinesis.processor.ShardRecordProcessorFactory;
import software.amazon.kinesis.processor.MultiStreamTracker;
import software.amazon.kinesis.retrieval.RetrievalConfig;

/**
 * This Builder is useful to create all configurations for the KCL with default values.
 */
@Data
@Accessors(fluent = true)
public class ConfigsBuilder {
    /**
     * Name of the stream to consume records from
     */
    @NonNull
    private final String streamName;
    /**
     * Application name for the KCL Worker
     */
    @NonNull
    private final String applicationName;
    /**
     * KinesisClient to be used to consumer records from Kinesis
     */
    @NonNull
    private final KinesisAsyncClient kinesisClient;
    /**
     * DynamoDBClient to be used to interact with DynamoDB service for lease management and checkpoiniting
     */
    @NonNull
    private final DynamoDbAsyncClient dynamoDBClient;
    /**
     * CloudWatchClient to be used to push KCL metrics to CloudWatch service
     */
    @NonNull
    private final CloudWatchAsyncClient cloudWatchClient;
    /**
     * KCL worker identifier to distinguish between 2 unique workers
     */
    @NonNull
    private final String workerIdentifier;
    /**
     * ShardRecordProcessorFactory to be used to create ShardRecordProcesor for processing records
     */
    @NonNull
    private final ShardRecordProcessorFactory shardRecordProcessorFactory;

    /**
     * Lease table name used for lease management and checkpointing.
     */
    private String tableName;

    /**
     * Lease table name used for lease management and checkpointing.
     *
     * @return DynamoDB table name
     */
    public String tableName() {
        if (StringUtils.isEmpty(tableName)) {
            tableName = applicationName();
        }
        return tableName;
    }

    /**
     * CloudWatch namespace for KCL metrics.
     */
    private String namespace;

    /**
     * CloudWatch namespace for KCL metrics.
     *
     * @return CloudWatch namespace
     */
    public String namespace() {
        if (StringUtils.isEmpty(namespace)) {
            namespace = applicationName();
        }
        return namespace;
    }

    private MultiStreamTracker multiStreamTracker;

    /**
     * Creates a new instance of CheckpointConfig
     *
     * @return CheckpointConfig
     */
    public CheckpointConfig checkpointConfig() {
        return new CheckpointConfig();
    }

    /**
     * Creates a new instance of CoordinatorConfig
     *
     * @return CoordinatorConfig
     */
    public CoordinatorConfig coordinatorConfig() {
        return new CoordinatorConfig(applicationName());
    }

    /**
     * Creates a new instance of LeaseManagementConfig
     *
     * @return LeaseManagementConfig
     */
    public LeaseManagementConfig leaseManagementConfig() {
        return new LeaseManagementConfig(tableName(), dynamoDBClient(), kinesisClient(), streamName(),
                workerIdentifier());
    }

    /**
     * Creates a new instance of LifecycleConfig
     *
     * @return LifecycleConfig
     */
    public LifecycleConfig lifecycleConfig() {
        return new LifecycleConfig();
    }

    /**
     * Creates a new instance of MetricsConfig
     *
     * @return MetricsConfig
     */
    public MetricsConfig metricsConfig() {
        return new MetricsConfig(cloudWatchClient(), namespace());
    }


    /**
     * Creates a new instance of ProcessorConfig
     *
     * @return ProcessorConfigConfig
     */
    public ProcessorConfig processorConfig() {
        return new ProcessorConfig(shardRecordProcessorFactory());
    }

    /**
     * Creates a new instance of RetrievalConfig
     *
     * @return RetrievalConfig
     */
    public RetrievalConfig retrievalConfig() {
        final RetrievalConfig retrievalConfig = new RetrievalConfig(kinesisClient(), streamName(), applicationName());
        if(this.multiStreamTracker != null) {
            retrievalConfig.multiStreamTracker(multiStreamTracker);
        }
        return retrievalConfig;
    }
}
