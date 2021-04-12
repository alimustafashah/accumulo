/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.coordinator;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.accumulo.core.compaction.thrift.CompactionState;
import org.apache.accumulo.core.metadata.TServerInstance;
import org.apache.accumulo.core.tabletserver.thrift.CompactionStats;
import org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob;

public class RunningCompaction {

  private final TExternalCompactionJob job;
  private final String compactorAddress;
  private final TServerInstance tserver;
  private final Map<Long,CompactionUpdate> updates = new TreeMap<>();
  private final AtomicBoolean completed = new AtomicBoolean(Boolean.FALSE);
  private CompactionStats stats = null;

  RunningCompaction(TExternalCompactionJob job, String compactorAddress, TServerInstance tserver) {
    super();
    this.job = job;
    this.compactorAddress = compactorAddress;
    this.tserver = tserver;
  }

  public Map<Long,CompactionUpdate> getUpdates() {
    return updates;
  }

  public void addUpdate(Long timestamp, String message, CompactionState state) {
    this.updates.put(timestamp, new CompactionUpdate(timestamp, message, state));
  }

  public CompactionStats getStats() {
    return stats;
  }

  public void setStats(CompactionStats stats) {
    this.stats = stats;
  }

  public TExternalCompactionJob getJob() {
    return job;
  }

  public String getCompactorAddress() {
    return compactorAddress;
  }

  public TServerInstance getTserver() {
    return tserver;
  }

  public boolean isCompleted() {
    return completed.get();
  }

  public void setCompleted() {
    completed.compareAndSet(false, true);
  }

}