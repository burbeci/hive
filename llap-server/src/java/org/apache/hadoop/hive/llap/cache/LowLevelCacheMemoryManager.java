/**
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

package org.apache.hadoop.hive.llap.cache;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.hive.llap.io.api.impl.LlapIoImpl;
import org.apache.hadoop.hive.llap.metrics.LlapDaemonCacheMetrics;

import com.google.common.annotations.VisibleForTesting;

/**
 * Implementation of memory manager for low level cache. Note that memory is released during
 * reserve most of the time, by calling the evictor to evict some memory. releaseMemory is
 * called rarely.
 */
public class LowLevelCacheMemoryManager implements MemoryManager {
  private final AtomicLong usedMemory;
  private final LowLevelCachePolicy evictor;
  private final LlapDaemonCacheMetrics metrics;
  private long maxSize;
  private LlapOomDebugDump memoryDumpRoot;

  private static final long LOCKING_DEBUG_DUMP_PERIOD_NS = 30 * 1000000000L; // 30 sec.
  private static final int LOCKING_DEBUG_DUMP_THRESHOLD = 5;
  private static final AtomicLong lastCacheDumpNs = new AtomicLong(0);

  public LowLevelCacheMemoryManager(
      long maxSize, LowLevelCachePolicy evictor, LlapDaemonCacheMetrics metrics) {
    this.maxSize = maxSize;
    this.evictor = evictor;
    this.usedMemory = new AtomicLong(0);
    this.metrics = metrics;
    if (LlapIoImpl.LOG.isInfoEnabled()) {
      LlapIoImpl.LOG.info("Memory manager initialized with max size {} and" +
          " {} ability to evict blocks", maxSize, ((evictor == null) ? "no " : ""));
    }
  }


  @Override
  public void reserveMemory(final long memoryToReserve) {
    boolean result = reserveMemory(memoryToReserve, true);
    if (result) return;
    // Can only happen if there's no evictor, or if thread is interrupted.
    throw new RuntimeException("Cannot reserve memory"
        + (Thread.currentThread().isInterrupted() ? "; thread interrupted" : ""));
  }

  @VisibleForTesting
  public boolean reserveMemory(final long memoryToReserve, boolean waitForEviction) {
    // TODO: if this cannot evict enough, it will spin infinitely. Terminate at some point?
    int badCallCount = 0;
    long evictedTotalMetric = 0, reservedTotalMetric = 0, remainingToReserve = memoryToReserve;
    boolean result = true;
    int waitTimeMs = 4;
    boolean didDumpIoState = false;
    while (remainingToReserve > 0) {
      long usedMem = usedMemory.get(), newUsedMem = usedMem + remainingToReserve;
      if (newUsedMem <= maxSize) {
        if (usedMemory.compareAndSet(usedMem, newUsedMem)) {
          reservedTotalMetric += remainingToReserve;
          break;
        }
        continue;
      }
      if (evictor == null) {
        result = false;
        break;
      }
      long evicted = evictor.evictSomeBlocks(remainingToReserve);
      if (evicted == 0) {
        if (!waitForEviction) {
          result = false;
          break; // Test code path where we don't do more than one attempt.
        }
        didDumpIoState = logEvictionIssue(++badCallCount, didDumpIoState);
        waitTimeMs = Math.min(1000, waitTimeMs << 1);
        assert waitTimeMs > 0;
        try {
          Thread.sleep(waitTimeMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          result = false;
          break;
        }
        continue;
      }

      evictedTotalMetric += evicted;
      badCallCount = 0;
      // Adjust the memory - we have to account for what we have just evicted.
      while (true) {
        long availableToReserveAfterEvict = maxSize - usedMem + evicted;
        long reservedAfterEvict = Math.min(remainingToReserve, availableToReserveAfterEvict);
        if (usedMemory.compareAndSet(usedMem, usedMem - evicted + reservedAfterEvict)) {
          remainingToReserve -= reservedAfterEvict;
          reservedTotalMetric += reservedAfterEvict;
          break;
        }
        usedMem = usedMemory.get();
      }

    }
    if (!result) {
      releaseMemory(reservedTotalMetric);
      reservedTotalMetric = 0;
    }
    metrics.incrCacheCapacityUsed(reservedTotalMetric - evictedTotalMetric);
    return result;
  }


  private boolean logEvictionIssue(int badCallCount, boolean didDumpIoState) {
    if (badCallCount <= LOCKING_DEBUG_DUMP_THRESHOLD) return didDumpIoState;
    String ioStateDump = maybeDumpIoState(didDumpIoState);
    if (ioStateDump == null) {
      LlapIoImpl.LOG.warn("Cannot evict blocks for " + badCallCount + " calls; cache full?");
      return didDumpIoState;
    } else {
      LlapIoImpl.LOG.warn("Cannot evict blocks; IO state:\n " + ioStateDump);
      return true;
    }
  }

  private String maybeDumpIoState(boolean didDumpIoState) {
    if (didDumpIoState) return null; // No more than once per reader.
    long now = System.nanoTime(), last = lastCacheDumpNs.get();
    while (true) {
      if (last != 0 && (now - last) < LOCKING_DEBUG_DUMP_PERIOD_NS) {
        return null; // We have recently dumped IO state into log.
      }
      if (lastCacheDumpNs.compareAndSet(last, now)) break;
      now = System.nanoTime();
      last = lastCacheDumpNs.get();
    }
    try {
      StringBuilder sb = new StringBuilder();
      memoryDumpRoot.debugDumpShort(sb);
      return sb.toString();
    } catch (Throwable t) {
      return "Failed to dump cache state: " + t.getClass() + " " + t.getMessage();
    }
  }


  @Override
  public long forceReservedMemory(int allocationSize, int count) {
    if (evictor == null) return 0;
    return evictor.tryEvictContiguousData(allocationSize, count);
  }

  @Override
  public void releaseMemory(final long memoryToRelease) {
    long oldV;
    do {
      oldV = usedMemory.get();
      assert oldV >= memoryToRelease;
    } while (!usedMemory.compareAndSet(oldV, oldV - memoryToRelease));
    metrics.incrCacheCapacityUsed(-memoryToRelease);
  }

  @Override
  public String debugDumpForOom() {
    if (evictor == null) return null;
    return "\ncache state\n" + evictor.debugDumpForOom();
  }

  @Override
  public void debugDumpShort(StringBuilder sb) {
    if (evictor == null) return;
    evictor.debugDumpShort(sb);
  }

  @Override
  public void updateMaxSize(long maxSize) {
    this.maxSize = maxSize;
  }


  public void setMemoryDumpRoot(LlapOomDebugDump memoryDumpRoot) {
    this.memoryDumpRoot = memoryDumpRoot;
  }
}
