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

package org.apache.flink.runtime.resourcemanager.slotmanager;

import org.apache.flink.api.common.resources.CPUResource;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.AkkaOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.ResourceManagerOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.resourcemanager.WorkerResourceSpec;
import org.apache.flink.util.ConfigurationException;
import org.apache.flink.util.Preconditions;

import java.math.RoundingMode;
import java.time.Duration;

import static org.apache.flink.configuration.TaskManagerOptions.TaskManagerLoadBalanceMode;

/** Configuration for the {@link SlotManager}. */
public class SlotManagerConfiguration {
    private final Time taskManagerRequestTimeout;
    private final Time taskManagerTimeout;
    private final Duration requirementCheckDelay;
    private final Duration declareNeededResourceDelay;
    private final boolean waitResultConsumedBeforeRelease;
    private final SlotMatchingStrategy slotMatchingStrategy;
    private final TaskManagerLoadBalanceMode taskManagerLoadBalanceMode;
    private final WorkerResourceSpec defaultWorkerResourceSpec;
    private final int numSlotsPerWorker;
    private final int minSlotNum;
    private final int maxSlotNum;
    private final CPUResource minTotalCpu;
    private final CPUResource maxTotalCpu;
    private final MemorySize minTotalMem;
    private final MemorySize maxTotalMem;
    private final int redundantTaskManagerNum;

    public SlotManagerConfiguration(
            Time taskManagerRequestTimeout,
            Time taskManagerTimeout,
            Duration requirementCheckDelay,
            Duration declareNeededResourceDelay,
            boolean waitResultConsumedBeforeRelease,
            SlotMatchingStrategy slotMatchingStrategy,
            TaskManagerLoadBalanceMode taskManagerLoadBalanceMode,
            WorkerResourceSpec defaultWorkerResourceSpec,
            int numSlotsPerWorker,
            int minSlotNum,
            int maxSlotNum,
            CPUResource minTotalCpu,
            CPUResource maxTotalCpu,
            MemorySize minTotalMem,
            MemorySize maxTotalMem,
            int redundantTaskManagerNum) {

        this.taskManagerRequestTimeout = Preconditions.checkNotNull(taskManagerRequestTimeout);
        this.taskManagerTimeout = Preconditions.checkNotNull(taskManagerTimeout);
        this.requirementCheckDelay = Preconditions.checkNotNull(requirementCheckDelay);
        this.declareNeededResourceDelay = Preconditions.checkNotNull(declareNeededResourceDelay);
        this.waitResultConsumedBeforeRelease = waitResultConsumedBeforeRelease;
        this.slotMatchingStrategy = Preconditions.checkNotNull(slotMatchingStrategy);
        this.taskManagerLoadBalanceMode = taskManagerLoadBalanceMode;
        this.defaultWorkerResourceSpec = Preconditions.checkNotNull(defaultWorkerResourceSpec);
        Preconditions.checkState(numSlotsPerWorker > 0);
        this.numSlotsPerWorker = numSlotsPerWorker;
        checkSlotNumResource(minSlotNum, maxSlotNum, defaultWorkerResourceSpec);
        checkTotalCPUResource(minTotalCpu, maxTotalCpu, defaultWorkerResourceSpec);
        checkTotalMemoryResource(minTotalMem, maxTotalMem, defaultWorkerResourceSpec);
        this.minSlotNum = minSlotNum;
        this.maxSlotNum = maxSlotNum;
        this.minTotalCpu = minTotalCpu;
        this.maxTotalCpu = maxTotalCpu;
        this.minTotalMem = minTotalMem;
        this.maxTotalMem = maxTotalMem;
        Preconditions.checkState(redundantTaskManagerNum >= 0);
        this.redundantTaskManagerNum = redundantTaskManagerNum;
    }

    private void checkSlotNumResource(
            int minSlotNum, int maxSlotNum, WorkerResourceSpec workerResourceSpec) {
        Preconditions.checkState(minSlotNum >= 0 && minSlotNum <= maxSlotNum);
        Preconditions.checkState(maxSlotNum > 0);

        if (minSlotNum == 0) {
            // no need to check resource stability
            return;
        }

        // cluster resource stability check.
        int minSlotWorkerNum =
                (int) Math.ceil((double) minSlotNum / workerResourceSpec.getNumSlots());
        int maxSlotWorkerNum =
                (int) Math.floor((double) maxSlotNum / workerResourceSpec.getNumSlots());

        Preconditions.checkState(minSlotWorkerNum <= maxSlotWorkerNum);
    }

    private void checkTotalCPUResource(
            CPUResource minTotalCpu,
            CPUResource maxTotalCpu,
            WorkerResourceSpec workerResourceSpec) {
        Preconditions.checkNotNull(minTotalCpu);
        Preconditions.checkNotNull(maxTotalCpu);
        Preconditions.checkState(maxTotalCpu.compareTo(minTotalCpu) >= 0);

        if (minTotalCpu.isZero()) {
            // no need to check resource stability
            return;
        }

        // cluster resource stability check.
        int minCPUWorkerNum =
                (int)
                        minTotalCpu
                                .getValue()
                                .divide(
                                        workerResourceSpec.getCpuCores().getValue(),
                                        0,
                                        RoundingMode.CEILING)
                                .doubleValue();

        int maxCPUWorkerNum =
                (int)
                        maxTotalCpu
                                .getValue()
                                .divide(
                                        workerResourceSpec.getCpuCores().getValue(),
                                        0,
                                        RoundingMode.FLOOR)
                                .doubleValue();

        Preconditions.checkState(minCPUWorkerNum <= maxCPUWorkerNum);
    }

    private void checkTotalMemoryResource(
            MemorySize minTotalMem, MemorySize maxTotalMem, WorkerResourceSpec workerResourceSpec) {
        Preconditions.checkNotNull(minTotalMem);
        Preconditions.checkNotNull(maxTotalMem);
        Preconditions.checkState(maxTotalMem.compareTo(minTotalMem) >= 0);

        if (minTotalMem.compareTo(MemorySize.ZERO) == 0) {
            // no need to check resource stability
            return;
        }

        // cluster resource stability check.
        int minMemoryWorkerNum =
                (int)
                        Math.ceil(
                                (double) minTotalMem.getBytes()
                                        / workerResourceSpec.getTotalMemSize().getBytes());

        int maxMemoryWorkerNum =
                (int)
                        Math.floor(
                                (double) maxTotalMem.getBytes()
                                        / workerResourceSpec.getTotalMemSize().getBytes());
        Preconditions.checkState(minMemoryWorkerNum <= maxMemoryWorkerNum);
    }

    public Time getTaskManagerRequestTimeout() {
        return taskManagerRequestTimeout;
    }

    public Time getTaskManagerTimeout() {
        return taskManagerTimeout;
    }

    public Duration getRequirementCheckDelay() {
        return requirementCheckDelay;
    }

    public Duration getDeclareNeededResourceDelay() {
        return declareNeededResourceDelay;
    }

    public boolean isWaitResultConsumedBeforeRelease() {
        return waitResultConsumedBeforeRelease;
    }

    public SlotMatchingStrategy getSlotMatchingStrategy() {
        return slotMatchingStrategy;
    }

    public TaskManagerLoadBalanceMode getTaskManagerLoadBalanceMode() {
        return taskManagerLoadBalanceMode;
    }

    public WorkerResourceSpec getDefaultWorkerResourceSpec() {
        return defaultWorkerResourceSpec;
    }

    public int getNumSlotsPerWorker() {
        return numSlotsPerWorker;
    }

    public int getMinSlotNum() {
        return minSlotNum;
    }

    public int getMaxSlotNum() {
        return maxSlotNum;
    }

    public CPUResource getMinTotalCpu() {
        return minTotalCpu;
    }

    public CPUResource getMaxTotalCpu() {
        return maxTotalCpu;
    }

    public MemorySize getMinTotalMem() {
        return minTotalMem;
    }

    public MemorySize getMaxTotalMem() {
        return maxTotalMem;
    }

    public int getRedundantTaskManagerNum() {
        return redundantTaskManagerNum;
    }

    public static SlotManagerConfiguration fromConfiguration(
            Configuration configuration, WorkerResourceSpec defaultWorkerResourceSpec)
            throws ConfigurationException {

        final Time rpcTimeout =
                Time.fromDuration(configuration.get(AkkaOptions.ASK_TIMEOUT_DURATION));

        final Time taskManagerTimeout =
                Time.milliseconds(configuration.get(ResourceManagerOptions.TASK_MANAGER_TIMEOUT));

        final Duration requirementCheckDelay =
                configuration.get(ResourceManagerOptions.REQUIREMENTS_CHECK_DELAY);

        final Duration declareNeededResourceDelay =
                configuration.get(ResourceManagerOptions.DECLARE_NEEDED_RESOURCE_DELAY);

        boolean waitResultConsumedBeforeRelease =
                configuration.get(ResourceManagerOptions.TASK_MANAGER_RELEASE_WHEN_RESULT_CONSUMED);

        TaskManagerLoadBalanceMode taskManagerLoadBalanceMode =
                TaskManagerLoadBalanceMode.loadFromConfiguration(configuration);
        final SlotMatchingStrategy slotMatchingStrategy =
                taskManagerLoadBalanceMode == TaskManagerLoadBalanceMode.SLOTS
                        ? LeastUtilizationSlotMatchingStrategy.INSTANCE
                        : AnyMatchingSlotMatchingStrategy.INSTANCE;

        int numSlotsPerWorker = configuration.get(TaskManagerOptions.NUM_TASK_SLOTS);

        int minSlotNum = configuration.get(ResourceManagerOptions.MIN_SLOT_NUM);
        int maxSlotNum = configuration.get(ResourceManagerOptions.MAX_SLOT_NUM);

        int redundantTaskManagerNum =
                configuration.get(ResourceManagerOptions.REDUNDANT_TASK_MANAGER_NUM);

        return new SlotManagerConfiguration(
                rpcTimeout,
                taskManagerTimeout,
                requirementCheckDelay,
                declareNeededResourceDelay,
                waitResultConsumedBeforeRelease,
                slotMatchingStrategy,
                taskManagerLoadBalanceMode,
                defaultWorkerResourceSpec,
                numSlotsPerWorker,
                minSlotNum,
                maxSlotNum,
                getMinTotalCpu(configuration, defaultWorkerResourceSpec, minSlotNum),
                getMaxTotalCpu(configuration, defaultWorkerResourceSpec, maxSlotNum),
                getMinTotalMem(configuration, defaultWorkerResourceSpec, minSlotNum),
                getMaxTotalMem(configuration, defaultWorkerResourceSpec, maxSlotNum),
                redundantTaskManagerNum);
    }

    private static CPUResource getMinTotalCpu(
            final Configuration configuration,
            final WorkerResourceSpec defaultWorkerResourceSpec,
            final int minSlotNum) {
        return configuration
                .getOptional(ResourceManagerOptions.MIN_TOTAL_CPU)
                .map(CPUResource::new)
                .orElseGet(
                        () ->
                                minSlotNum == 0
                                        ? new CPUResource(Double.MIN_VALUE)
                                        : defaultWorkerResourceSpec
                                                .getCpuCores()
                                                .multiply(minSlotNum)
                                                .divide(defaultWorkerResourceSpec.getNumSlots()));
    }

    private static CPUResource getMaxTotalCpu(
            final Configuration configuration,
            final WorkerResourceSpec defaultWorkerResourceSpec,
            final int maxSlotNum) {
        return configuration
                .getOptional(ResourceManagerOptions.MAX_TOTAL_CPU)
                .map(CPUResource::new)
                .orElseGet(
                        () ->
                                maxSlotNum == Integer.MAX_VALUE
                                        ? new CPUResource(Double.MAX_VALUE)
                                        : defaultWorkerResourceSpec
                                                .getCpuCores()
                                                .multiply(maxSlotNum)
                                                .divide(defaultWorkerResourceSpec.getNumSlots()));
    }

    private static MemorySize getMinTotalMem(
            final Configuration configuration,
            final WorkerResourceSpec defaultWorkerResourceSpec,
            final int minSlotNum) {
        return configuration
                .getOptional(ResourceManagerOptions.MIN_TOTAL_MEM)
                .orElseGet(
                        () ->
                                minSlotNum == 0
                                        ? MemorySize.ZERO
                                        : defaultWorkerResourceSpec
                                                .getTotalMemSize()
                                                .multiply(minSlotNum)
                                                .divide(defaultWorkerResourceSpec.getNumSlots()));
    }

    private static MemorySize getMaxTotalMem(
            final Configuration configuration,
            final WorkerResourceSpec defaultWorkerResourceSpec,
            final int maxSlotNum) {
        return configuration
                .getOptional(ResourceManagerOptions.MAX_TOTAL_MEM)
                .orElseGet(
                        () ->
                                maxSlotNum == Integer.MAX_VALUE
                                        ? MemorySize.MAX_VALUE
                                        : defaultWorkerResourceSpec
                                                .getTotalMemSize()
                                                // In theory, there is a possibility of long
                                                // overflow here. However, in actual scenarios, for
                                                // a 1TB of TM memory and a very large number of
                                                // maxSlotNum (e.g. 1_000_000), there is still no
                                                // overflow.
                                                .multiply(maxSlotNum)
                                                .divide(defaultWorkerResourceSpec.getNumSlots()));
    }
}
