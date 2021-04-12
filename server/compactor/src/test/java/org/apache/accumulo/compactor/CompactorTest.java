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
package org.apache.accumulo.compactor;

import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.apache.accumulo.core.compaction.thrift.CompactionState;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.dataImpl.thrift.TKeyExtent;
import org.apache.accumulo.core.metadata.schema.ExternalCompactionId;
import org.apache.accumulo.core.tabletserver.thrift.CompactionStats;
import org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob;
import org.apache.accumulo.core.util.Halt;
import org.apache.accumulo.core.util.HostAndPort;
import org.apache.accumulo.fate.util.UtilWaitThread;
import org.apache.accumulo.server.AbstractServer;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.compaction.RetryableThriftCall.RetriesExceededException;
import org.apache.accumulo.server.fs.VolumeManagerImpl;
import org.apache.accumulo.server.rpc.ServerAddress;
import org.apache.accumulo.server.rpc.TServerUtils;
import org.apache.zookeeper.KeeperException;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.core.classloader.annotations.SuppressStaticInitializationFor;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Compactor.class})
@SuppressStaticInitializationFor({"org.apache.log4j.LogManager"})
@PowerMockIgnore({"org.slf4j.*", "org.apache.logging.*", "org.apache.log4j.*",
    "org.apache.commons.logging.*", "org.xml.*", "javax.xml.*", "org.w3c.dom.*",
    "com.sun.org.apache.xerces.*"})
public class CompactorTest {

  public class SuccessfulCompaction implements Runnable {

    protected final Logger LOG = LoggerFactory.getLogger(this.getClass());

    protected final LongAdder totalInputEntries;
    protected final LongAdder totalInputBytes;
    protected final CountDownLatch started;
    protected final CountDownLatch stopped;
    protected final AtomicReference<Throwable> err;

    public SuccessfulCompaction(LongAdder totalInputEntries, LongAdder totalInputBytes,
        CountDownLatch started, CountDownLatch stopped, AtomicReference<Throwable> err) {
      this.totalInputEntries = totalInputEntries;
      this.totalInputBytes = totalInputBytes;
      this.err = err;
      this.started = started;
      this.stopped = stopped;
    }

    @Override
    public void run() {
      try {
        started.countDown();
        UtilWaitThread.sleep(1000);
      } catch (Exception e) {
        err.set(e);
      } finally {
        stopped.countDown();
      }
    }
  }

  public class FailedCompaction extends SuccessfulCompaction {

    public FailedCompaction(LongAdder totalInputEntries, LongAdder totalInputBytes,
        CountDownLatch started, CountDownLatch stopped, AtomicReference<Throwable> err) {
      super(totalInputEntries, totalInputBytes, started, stopped, err);
    }

    @Override
    public void run() {
      try {
        started.countDown();
        UtilWaitThread.sleep(1000);
        throw new RuntimeException();
      } catch (Exception e) {
        err.set(e);
      } finally {
        stopped.countDown();
      }
    }
  }

  public class InterruptedCompaction extends SuccessfulCompaction {

    public InterruptedCompaction(LongAdder totalInputEntries, LongAdder totalInputBytes,
        CountDownLatch started, CountDownLatch stopped, AtomicReference<Throwable> err) {
      super(totalInputEntries, totalInputBytes, started, stopped, err);
    }

    @Override
    public void run() {
      try {
        started.countDown();
        final Thread thread = Thread.currentThread();
        Timer t = new Timer();
        TimerTask task = new TimerTask() {
          @Override
          public void run() {
            thread.interrupt();
          }
        };
        t.schedule(task, 250);
        Thread.sleep(1000);
      } catch (Exception e) {
        LOG.error("Compaction failed: {}", e.getMessage());
        err.set(e);
        throw new RuntimeException("Compaction failed", e);
      } finally {
        stopped.countDown();
      }
    }

  }

  public class SuccessfulCompactor extends Compactor {

    private final Logger LOG = LoggerFactory.getLogger(SuccessfulCompactor.class);

    private final Supplier<UUID> uuid;
    private final ServerAddress address;
    private final TExternalCompactionJob job;
    private final AccumuloConfiguration conf;
    private final ServerContext ctx;
    private final ExternalCompactionId eci;
    private volatile boolean completedCalled = false;
    private volatile boolean failedCalled = false;
    private CompactionState latestState = null;

    SuccessfulCompactor(Supplier<UUID> uuid, ServerAddress address, TExternalCompactionJob job,
        AccumuloConfiguration conf, ServerContext ctx, ExternalCompactionId eci) {
      super(new CompactorServerOpts(), new String[] {"-q", "testQ"});
      this.uuid = uuid;
      this.address = address;
      this.job = job;
      this.conf = conf;
      this.ctx = ctx;
      this.eci = eci;
    }

    @Override
    protected void setupSecurity() {}

    @Override
    protected void startGCLogger() {}

    @Override
    protected void printStartupMsg() {}

    @Override
    public ServerContext getContext() {
      return this.ctx;
    }

    @Override
    public AccumuloConfiguration getConfiguration() {
      return this.conf;
    }

    @Override
    protected void announceExistence(HostAndPort clientAddress)
        throws KeeperException, InterruptedException {}

    @Override
    protected ServerAddress startCompactorClientService() throws UnknownHostException {
      return this.address;
    }

    @Override
    protected TExternalCompactionJob getNextJob(Supplier<UUID> uuid)
        throws RetriesExceededException {
      LOG.info("Attempting to get next job, eci = {}", eci);
      currentCompactionId.set(eci);
      this.shutdown = true;
      return job;
    }

    @Override
    protected Runnable createCompactionJob(TExternalCompactionJob job, LongAdder totalInputEntries,
        LongAdder totalInputBytes, CountDownLatch started, CountDownLatch stopped,
        AtomicReference<Throwable> err) {
      return new SuccessfulCompaction(totalInputEntries, totalInputBytes, started, stopped, err);
    }

    @Override
    protected Supplier<UUID> getNextId() {
      return uuid;
    }

    @Override
    protected void updateCompactionState(TExternalCompactionJob job, CompactionState state,
        String message) throws RetriesExceededException {
      latestState = state;
    }

    @Override
    protected void updateCompactionFailed(TExternalCompactionJob job)
        throws RetriesExceededException {
      failedCalled = true;
    }

    @Override
    protected void updateCompactionCompleted(TExternalCompactionJob job, CompactionStats stats)
        throws RetriesExceededException {
      completedCalled = true;
    }

    public CompactionState getLatestState() {
      return latestState;
    }

    public boolean isCompletedCalled() {
      return completedCalled;
    }

    public boolean isFailedCalled() {
      return failedCalled;
    }

  }

  public class FailedCompactor extends SuccessfulCompactor {

    FailedCompactor(Supplier<UUID> uuid, ServerAddress address, TExternalCompactionJob job,
        AccumuloConfiguration conf, ServerContext ctx, ExternalCompactionId eci) {
      super(uuid, address, job, conf, ctx, eci);
    }

    @Override
    protected Runnable createCompactionJob(TExternalCompactionJob job, LongAdder totalInputEntries,
        LongAdder totalInputBytes, CountDownLatch started, CountDownLatch stopped,
        AtomicReference<Throwable> err) {
      return new FailedCompaction(totalInputEntries, totalInputBytes, started, stopped, err);
    }
  }

  public class InterruptedCompactor extends SuccessfulCompactor {

    InterruptedCompactor(Supplier<UUID> uuid, ServerAddress address, TExternalCompactionJob job,
        AccumuloConfiguration conf, ServerContext ctx, ExternalCompactionId eci) {
      super(uuid, address, job, conf, ctx, eci);
    }

    @Override
    protected Runnable createCompactionJob(TExternalCompactionJob job, LongAdder totalInputEntries,
        LongAdder totalInputBytes, CountDownLatch started, CountDownLatch stopped,
        AtomicReference<Throwable> err) {
      return new InterruptedCompaction(totalInputEntries, totalInputBytes, started, stopped, err);
    }

  }

  @Test
  public void testCheckTime() throws Exception {
    // Instantiates class without calling constructor
    Compactor c = Whitebox.newInstance(Compactor.class);
    Assert.assertEquals(1, c.calculateProgressCheckTime(1024));
    Assert.assertEquals(1, c.calculateProgressCheckTime(1048576));
    Assert.assertEquals(1, c.calculateProgressCheckTime(10485760));
    Assert.assertEquals(10, c.calculateProgressCheckTime(104857600));
    Assert.assertEquals(102, c.calculateProgressCheckTime(1024 * 1024 * 1024));
  }

  @Test
  public void testCompactionSucceeds() throws Exception {
    UUID uuid = UUID.randomUUID();
    Supplier<UUID> supplier = new Supplier<>() {
      @Override
      public UUID get() {
        return uuid;
      }
    };

    ExternalCompactionId eci = ExternalCompactionId.generate(supplier.get());

    PowerMock.resetAll();
    PowerMock.suppress(PowerMock.methods(Halt.class, "halt"));
    PowerMock.suppress(PowerMock.methods(TServerUtils.class, "stopTServer"));
    PowerMock.suppress(PowerMock.constructor(AbstractServer.class));

    ServerAddress client = PowerMock.createNiceMock(ServerAddress.class);
    HostAndPort address = HostAndPort.fromString("localhost:10240");
    EasyMock.expect(client.getAddress()).andReturn(address);

    TExternalCompactionJob job = PowerMock.createNiceMock(TExternalCompactionJob.class);
    TKeyExtent extent = PowerMock.createNiceMock(TKeyExtent.class);
    EasyMock.expect(job.isSetExternalCompactionId()).andReturn(true).anyTimes();
    EasyMock.expect(job.getExternalCompactionId()).andReturn(eci.toString()).anyTimes();
    EasyMock.expect(job.getExtent()).andReturn(extent);

    AccumuloConfiguration conf = PowerMock.createNiceMock(AccumuloConfiguration.class);
    EasyMock.expect(conf.getTimeInMillis(Property.INSTANCE_ZK_TIMEOUT)).andReturn(86400000L);

    ServerContext ctx = PowerMock.createNiceMock(ServerContext.class);
    VolumeManagerImpl vm = PowerMock.createNiceMock(VolumeManagerImpl.class);
    EasyMock.expect(ctx.getVolumeManager()).andReturn(vm);
    vm.close();

    PowerMock.replayAll();

    SuccessfulCompactor c = new SuccessfulCompactor(supplier, client, job, conf, ctx, eci);
    c.run();

    PowerMock.verifyAll();
    c.close();

    Assert.assertTrue(c.isCompletedCalled());
    Assert.assertFalse(c.isFailedCalled());
  }

  @Test
  public void testCompactionFails() throws Exception {
    UUID uuid = UUID.randomUUID();
    Supplier<UUID> supplier = new Supplier<>() {
      @Override
      public UUID get() {
        return uuid;
      }
    };

    ExternalCompactionId eci = ExternalCompactionId.generate(supplier.get());

    PowerMock.resetAll();
    PowerMock.suppress(PowerMock.methods(Halt.class, "halt"));
    PowerMock.suppress(PowerMock.methods(TServerUtils.class, "stopTServer"));
    PowerMock.suppress(PowerMock.constructor(AbstractServer.class));

    ServerAddress client = PowerMock.createNiceMock(ServerAddress.class);
    HostAndPort address = HostAndPort.fromString("localhost:10240");
    EasyMock.expect(client.getAddress()).andReturn(address);

    TExternalCompactionJob job = PowerMock.createNiceMock(TExternalCompactionJob.class);
    TKeyExtent extent = PowerMock.createNiceMock(TKeyExtent.class);
    EasyMock.expect(job.isSetExternalCompactionId()).andReturn(true).anyTimes();
    EasyMock.expect(job.getExternalCompactionId()).andReturn(eci.toString()).anyTimes();
    EasyMock.expect(job.getExtent()).andReturn(extent);

    AccumuloConfiguration conf = PowerMock.createNiceMock(AccumuloConfiguration.class);
    EasyMock.expect(conf.getTimeInMillis(Property.INSTANCE_ZK_TIMEOUT)).andReturn(86400000L);

    ServerContext ctx = PowerMock.createNiceMock(ServerContext.class);
    VolumeManagerImpl vm = PowerMock.createNiceMock(VolumeManagerImpl.class);
    EasyMock.expect(ctx.getVolumeManager()).andReturn(vm);
    vm.close();

    PowerMock.replayAll();

    FailedCompactor c = new FailedCompactor(supplier, client, job, conf, ctx, eci);
    c.run();

    PowerMock.verifyAll();
    c.close();

    Assert.assertFalse(c.isCompletedCalled());
    Assert.assertTrue(c.isFailedCalled());
    Assert.assertEquals(CompactionState.FAILED, c.getLatestState());
  }

  @Test
  public void testCompactionInterrupted() throws Exception {
    UUID uuid = UUID.randomUUID();
    Supplier<UUID> supplier = new Supplier<>() {
      @Override
      public UUID get() {
        return uuid;
      }
    };

    ExternalCompactionId eci = ExternalCompactionId.generate(supplier.get());

    PowerMock.resetAll();
    PowerMock.suppress(PowerMock.methods(Halt.class, "halt"));
    PowerMock.suppress(PowerMock.methods(TServerUtils.class, "stopTServer"));
    PowerMock.suppress(PowerMock.constructor(AbstractServer.class));

    ServerAddress client = PowerMock.createNiceMock(ServerAddress.class);
    HostAndPort address = HostAndPort.fromString("localhost:10240");
    EasyMock.expect(client.getAddress()).andReturn(address);

    TExternalCompactionJob job = PowerMock.createNiceMock(TExternalCompactionJob.class);
    TKeyExtent extent = PowerMock.createNiceMock(TKeyExtent.class);
    EasyMock.expect(job.isSetExternalCompactionId()).andReturn(true).anyTimes();
    EasyMock.expect(job.getExternalCompactionId()).andReturn(eci.toString()).anyTimes();
    EasyMock.expect(job.getExtent()).andReturn(extent);

    AccumuloConfiguration conf = PowerMock.createNiceMock(AccumuloConfiguration.class);
    EasyMock.expect(conf.getTimeInMillis(Property.INSTANCE_ZK_TIMEOUT)).andReturn(86400000L);

    ServerContext ctx = PowerMock.createNiceMock(ServerContext.class);
    VolumeManagerImpl vm = PowerMock.createNiceMock(VolumeManagerImpl.class);
    EasyMock.expect(ctx.getVolumeManager()).andReturn(vm);
    vm.close();

    PowerMock.replayAll();

    InterruptedCompactor c = new InterruptedCompactor(supplier, client, job, conf, ctx, eci);
    c.run();

    PowerMock.verifyAll();
    c.close();

    Assert.assertFalse(c.isCompletedCalled());
    Assert.assertTrue(c.isFailedCalled());
    Assert.assertEquals(CompactionState.CANCELLED, c.getLatestState());
  }

}