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

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.io.network.buffer.Buffer;

import org.apache.flink.shaded.guava32.com.google.common.collect.BiMap;
import org.apache.flink.shaded.guava32.com.google.common.collect.HashBiMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

/**
 * A wrapper to union the output from multiple {@link ResultSubpartitionView}s. This class provides
 * the following guarantees to the output buffers.
 *
 * <ul>
 *   <li>Each output buffer corresponds to a buffer in one of the subpartitions.
 *   <li>Buffers in the same subpartition are output without their order changed.
 *   <li>If a record is split and placed into multiple adjacent buffers due to the capacity limit of
 *       the buffer, these buffers will be output consecutively without the entry of buffers from
 *       other subpartitions in between.
 * </ul>
 */
public class UnionResultSubpartitionView
        implements ResultSubpartitionView, BufferAvailabilityListener {
    private static final Logger LOG = LoggerFactory.getLogger(UnionResultSubpartitionView.class);

    /** The maximum number of buffers to be cached in an instance of this class. */
    private static final int CACHE_CAPACITY = 10;

    private final Object lock = new Object();

    /** All the {@link ResultSubpartitionView}s managed by this class. */
    private final BiMap<Integer, ResultSubpartitionView> allViews = HashBiMap.create();

    /** All the {@link ResultSubpartitionView}s that have data available. */
    private final SubpartitionSelector<ResultSubpartitionView> availableViews =
            new RoundRobinSubpartitionSelector<>();

    private final BufferAvailabilityListener availabilityListener;

    /**
     * A queue containing buffers cached from the wrapped subpartition views, and the subpartition
     * where each buffer comes from. Cache is used to provide the data type of the next buffer and
     * an estimation of the backlog, as required by {@link ResultSubpartition.BufferAndBacklog}.
     */
    private final Queue<Tuple2<ResultSubpartition.BufferAndBacklog, Integer>> cachedBuffers =
            new LinkedList<>();

    private boolean isReleased;

    private int sequenceNumber;

    public UnionResultSubpartitionView(BufferAvailabilityListener availabilityListener) {
        this.availabilityListener = availabilityListener;
        this.isReleased = false;
        this.sequenceNumber = 0;
    }

    public void notifyViewCreated(int subpartitionId, ResultSubpartitionView view) {
        allViews.put(subpartitionId, view);
    }

    @Override
    public int peekNextBufferSubpartitionId() throws IOException {
        synchronized (lock) {
            cacheBuffer();
            return cachedBuffers.isEmpty() ? -1 : cachedBuffers.peek().f1;
        }
    }

    @Nullable
    @Override
    public ResultSubpartition.BufferAndBacklog getNextBuffer() throws IOException {
        synchronized (lock) {
            cacheBuffer();
            if (cachedBuffers.isEmpty()) {
                return null;
            }

            ResultSubpartition.BufferAndBacklog buffer = cachedBuffers.poll().f0;

            return new ResultSubpartition.BufferAndBacklog(
                    buffer.buffer(),
                    cachedBuffers.size(),
                    cachedBuffers.isEmpty()
                            ? Buffer.DataType.NONE
                            : cachedBuffers.peek().f0.buffer().getDataType(),
                    sequenceNumber++);
        }
    }

    private void cacheBuffer() throws IOException {
        while (cachedBuffers.size() < CACHE_CAPACITY) {
            final ResultSubpartitionView currentView =
                    availableViews.getNextSubpartitionToConsume();
            if (currentView == null) {
                break;
            }

            final ResultSubpartition.BufferAndBacklog buffer = currentView.getNextBuffer();
            if (buffer == null) {
                availableViews.markLastConsumptionStatus(false, false);
                if (!availableViews.isMoreSubpartitionSwitchable()) {
                    break;
                } else {
                    continue;
                }
            }

            availableViews.markLastConsumptionStatus(
                    true, buffer.buffer().getDataType().isPartialRecord());

            cachedBuffers.add(Tuple2.of(buffer, allViews.inverse().get(currentView)));
        }
    }

    @Override
    public void notifyDataAvailable() {
        // This method should not be exposed in any form.
        throw new UnsupportedOperationException("Method should never be called.");
    }

    @Override
    public void notifyDataAvailable(ResultSubpartitionView view) {
        synchronized (lock) {
            if (!availableViews.notifyDataAvailable(view) || !cachedBuffers.isEmpty()) {
                // The availabilityListener has already been notified.
                return;
            }

            try {
                cacheBuffer();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            if (cachedBuffers.isEmpty()) {
                return;
            }
        }
        availabilityListener.notifyDataAvailable(this);
    }

    @Override
    public void notifyPriorityEvent(int priorityBufferNumber) {
        // Only used by pipelined shuffle, which is not supported by this class yet.
        throw new UnsupportedOperationException("Method should never be called.");
    }

    @Override
    public void releaseAllResources() throws IOException {
        for (ResultSubpartitionView view : allViews.values()) {
            view.releaseAllResources();
        }
        isReleased = true;
    }

    @Override
    public boolean isReleased() {
        return isReleased;
    }

    @Override
    public void resumeConsumption() {
        // Only used by pipelined shuffle, which is not supported by this class yet.
        throw new UnsupportedOperationException("Method should never be called.");
    }

    @Override
    public void acknowledgeAllDataProcessed() {
        // Not used by tiered hybrid shuffle, which is not supported by this class yet.
        throw new UnsupportedOperationException("Method should never be called.");
    }

    @Override
    public Throwable getFailureCause() {
        Throwable cause = null;
        for (ResultSubpartitionView view : allViews.values()) {
            if (view.getFailureCause() != null) {
                cause = view.getFailureCause();
                LOG.error(cause.toString());
            }
        }
        return cause;
    }

    @Override
    public AvailabilityWithBacklog getAvailabilityAndBacklog(boolean isCreditAvailable) {
        synchronized (lock) {
            try {
                cacheBuffer();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (cachedBuffers.isEmpty()) {
                return new AvailabilityWithBacklog(false, 0);
            }

            return new AvailabilityWithBacklog(
                    isCreditAvailable || cachedBuffers.peek().f0.buffer().getDataType().isEvent(),
                    (int)
                            cachedBuffers.stream()
                                    .filter(x -> x.f0.buffer().getDataType().isBuffer())
                                    .count());
        }
    }

    @Override
    public void notifyRequiredSegmentId(int subpartitionId, int segmentId) {
        synchronized (lock) {
            allViews.get(subpartitionId).notifyRequiredSegmentId(subpartitionId, segmentId);
        }
    }

    @Override
    public int unsynchronizedGetNumberOfQueuedBuffers() {
        return cachedBuffers.size();
    }

    @Override
    public int getNumberOfQueuedBuffers() {
        synchronized (lock) {
            return cachedBuffers.size();
        }
    }

    @Override
    public void notifyNewBufferSize(int newBufferSize) {
        for (ResultSubpartitionView view : allViews.values()) {
            view.notifyNewBufferSize(newBufferSize);
        }
    }
}
