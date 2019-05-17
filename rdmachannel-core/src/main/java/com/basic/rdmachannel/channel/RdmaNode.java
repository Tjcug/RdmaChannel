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

package com.basic.rdmachannel.channel;

import com.basic.rdmachannel.mr.RdmaBuffer;
import com.basic.rdmachannel.mr.RdmaBufferManager;
import com.basic.rdmachannel.token.RegionToken;
import com.ibm.disni.rdma.verbs.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class RdmaNode {
  private static final Logger logger = LoggerFactory.getLogger(RdmaNode.class);
  private static final int BACKLOG = 128;

  private final RdmaChannelConf conf;
  //保存主动连接其他RDMANode的K,V键值对
  private final ConcurrentHashMap<InetSocketAddress, RdmaChannel> activeRdmaChannelMap =
    new ConcurrentHashMap<>();
  //保存被动接受其他RDMANode的连接的K,V键值对
  public final ConcurrentHashMap<InetSocketAddress, RdmaChannel> passiveRdmaChannelMap =
    new ConcurrentHashMap<>();

  public final ConcurrentHashMap<String, InetSocketAddress> passiveRdmaInetSocketMap =
          new ConcurrentHashMap<>();

  private RdmaBufferManager rdmaBufferManager = null;
  private RdmaCmId listenerRdmaCmId;//通信Channel ID
  private RdmaEventChannel cmChannel;//RDMA EventChannel
  private final AtomicBoolean runThread = new AtomicBoolean(false);
  private Thread listeningThread;
  private IbvPd ibvPd;// ibv 保护域 用来注册内存用
  private InetSocketAddress localInetSocketAddress;
  private final ArrayList<Integer> cpuArrayList = new ArrayList<>();
  private int cpuIndex = 0;
  private RdmaChannel.RdmaChannelType rdmaChannelType;
  private String hostName;//本地主机名

  public RdmaNode(String hostName, int port, final RdmaChannelConf conf,RdmaChannel.RdmaChannelType rdmaChannelType) throws Exception {
    this.conf = conf;
    this.rdmaChannelType = rdmaChannelType;
    this.hostName=hostName;

    cmChannel = RdmaEventChannel.createEventChannel();
    if (this.cmChannel == null) {
      throw new IOException("Unable to allocate RDMA Event Channel");
    }

    this.listenerRdmaCmId = cmChannel.createId(RdmaCm.RDMA_PS_TCP);
    if (this.listenerRdmaCmId == null) {
      throw new IOException("Unable to allocate RDMA CM Id");
    }

    try {

    int err = 0;
    int bindPort = port;

    //RdmaCmId 绑定到本地InetAddress
    for (int i = 0; i < conf.portMaxRetries(); i++) {
      err = listenerRdmaCmId.bindAddr(
              new InetSocketAddress(InetAddress.getByName(hostName), bindPort));
      if (err == 0) {
        break;
      }
      logger.info("Failed to bind to port {} on iteration {}", bindPort, i);
      bindPort = bindPort != 0 ? bindPort + 1 : 0;
    }

    logger.info(""+err);
    if (err != 0 || listenerRdmaCmId.getVerbs() == null){
      throw new IOException("Failed to bind. Make sure your NIC supports RDMA");
    }

    err = listenerRdmaCmId.listen(BACKLOG);
    if (err != 0) {
      throw new IOException("Failed to start listener: " + err);
    }

    localInetSocketAddress = (InetSocketAddress) listenerRdmaCmId.getSource();

    ibvPd = listenerRdmaCmId.getVerbs().allocPd();
    if (ibvPd == null) {
      throw new IOException("Failed to create PD");
    }

    //创建一个RDMA
    this.rdmaBufferManager = new RdmaBufferManager(ibvPd, conf);
  } catch (IOException e) {
    logger.error("Failed in RdmaNode constructor");
    stop();
    throw e;
  } catch (UnsatisfiedLinkError ule) {
    logger.error("libdisni not found! It must be installed within the java.library.path on each" +
            " Executor and Driver instance");
    throw ule;
  }


  initCpuArrayList();
  }

  /**
   * 接受Connect事件监听完成事件
   * @param connectListener
   */
  public void bindConnectCompleteListener(final RdmaConnectListener connectListener) throws Exception {

    listeningThread = new Thread(() -> {
      logger.info("Starting RdmaNode Listening Server, listening on: " + localInetSocketAddress);

      final int teardownListenTimeout = conf.teardownListenTimeout();
      while (runThread.get()) {
        try {
          // Wait for next event
          RdmaCmEvent event = cmChannel.getCmEvent(teardownListenTimeout);
          if (event == null) {
            continue;
          }

          RdmaCmId cmId = event.getConnIdPriv();
          int eventType = event.getEvent();
          event.ackEvent();

          InetSocketAddress inetSocketAddress = (InetSocketAddress)cmId.getDestination();

          if (eventType == RdmaCmEvent.EventType.RDMA_CM_EVENT_CONNECT_REQUEST.ordinal()) {
            RdmaChannel rdmaChannel = passiveRdmaChannelMap.get(inetSocketAddress);
            if (rdmaChannel != null) {
              if (rdmaChannel.isError()) {
                logger.warn("Received a redundant RDMA connection request from " +
                        inetSocketAddress + ", which already has an older connection in error state." +
                        " Removing the old connection and creating a new one");
                passiveRdmaChannelMap.remove(inetSocketAddress);
                passiveRdmaInetSocketMap.remove(inetSocketAddress.getHostName());
                rdmaChannel.stop();
              } else {
                logger.warn("Received a redundant RDMA connection request from " +
                        inetSocketAddress + ", rejecting the request");
                // TODO: Add reject initiation code once disni implements/exports reject
                continue;
              }
            }

            rdmaChannel = new RdmaChannel(rdmaChannelType, conf, rdmaBufferManager,
                    cmId, getNextCpuVector());
            if (passiveRdmaChannelMap.putIfAbsent(inetSocketAddress, rdmaChannel) != null) {
              logger.warn("Race in creating an RDMA Channel for " + inetSocketAddress);
              rdmaChannel.stop();
              continue;
            }

//            RdmaChannel previous = activeRdmaChannelMap.put(inetSocketAddress, rdmaChannel);
//            if (previous != null) {
//              previous.stop();
//            }

            try {
              rdmaChannel.accept();
              //Accept
            } catch (IOException ioe) {
              logger.error("Error in accept call on a passive RdmaChannel: " + ioe);
              passiveRdmaChannelMap.remove(inetSocketAddress);
              passiveRdmaInetSocketMap.remove(inetSocketAddress.getAddress().getHostAddress());
              rdmaChannel.stop();
            }
          } else if (eventType == RdmaCmEvent.EventType.RDMA_CM_EVENT_ESTABLISHED.ordinal()) {
            RdmaChannel rdmaChannel = passiveRdmaChannelMap.get(inetSocketAddress);
            if (rdmaChannel == null) {
              logger.warn("Received an RDMA CM Established Event from " + inetSocketAddress +
                      ", which has no local matching connection. Ignoring");
              continue;
            }

            if (rdmaChannel.isError()) {
              logger.warn("Received a redundant RDMA connection request from " + inetSocketAddress +
                      ", with a local connection in error state. Removing the old connection and " +
                      "aborting");
              passiveRdmaChannelMap.remove(inetSocketAddress);
              passiveRdmaInetSocketMap.remove(inetSocketAddress.getAddress().getHostAddress());
              rdmaChannel.stop();
            } else {
              logger.info(" rdmaChannel finalizeConnection");
              rdmaChannel.finalizeConnection();
              connectListener.onSuccess(inetSocketAddress,rdmaChannel);
            }
          } else if (eventType == RdmaCmEvent.EventType.RDMA_CM_EVENT_DISCONNECTED.ordinal()) {
            RdmaChannel rdmaChannel = passiveRdmaChannelMap.remove(inetSocketAddress);
            passiveRdmaInetSocketMap.remove(inetSocketAddress.getAddress().getHostAddress());
            if (rdmaChannel == null) {
              logger.info("Received an RDMA CM Disconnect Event from " + inetSocketAddress +
                      ", which has no local matching connection. Ignoring");
              continue;
            }

            rdmaChannel.stop();
          } else {
            logger.info("Received an unexpected CM Event {}", eventType);
          }
        } catch (Exception e) {
          e.printStackTrace();
          connectListener.onFailure(e);
          throw new RuntimeException("Exception in RdmaNode listening thread " + e);
        }
      }
      logger.info("Exiting RdmaNode Listening Server");
    },
            "RdmaNode connection listening thread");

    listeningThread.setDaemon(true);
    listeningThread.start();

    runThread.set(true);
  }

  private void initCpuArrayList() throws IOException {
    logger.info("cpuList from configuration file: {}", conf.cpuList());

    java.util.function.Consumer<Integer> addCpuToList = (cpu) -> {
      // Add CPUs to the list while shuffling the order of the list,
      // so that multiple RdmaNodes on this machine will have a better change
      // to getRdmaBlockLocation different CPUs assigned to them
      cpuArrayList.add(
              cpuArrayList.isEmpty() ? 0 : new Random().nextInt(cpuArrayList.size()),
              cpu);
    };

    final int maxCpu = Runtime.getRuntime().availableProcessors() - 1;
    final int maxUsableCpu = Math.min(Runtime.getRuntime().availableProcessors(),
            listenerRdmaCmId.getVerbs().getNumCompVectors()) - 1;
    if (maxUsableCpu < maxCpu - 1) {
      logger.warn("IbvContext supports only " + (maxUsableCpu + 1) + " CPU cores, while there are" +
              " " + (maxCpu + 1) + " CPU cores in the system. This may lead to under-utilization of the" +
              " system's CPU cores. This limitation may be adjustable in the RDMA device configuration.");
    }

    for (String cpuRange : conf.cpuList().split(",")) {
      final String[] cpuRangeArray = cpuRange.split("-");
      int cpuStart, cpuEnd;

      try {
        cpuStart = cpuEnd = Integer.parseInt(cpuRangeArray[0].trim());
        if (cpuRangeArray.length > 1) {
          cpuEnd = Integer.parseInt(cpuRangeArray[1].trim());
        }

        if (cpuStart > cpuEnd || cpuEnd > maxCpu) {
          logger.warn("Invalid cpuList!  start: {}, end: {}, max: {}", cpuStart, cpuEnd, maxCpu);
          throw new NumberFormatException();
        }
      } catch (NumberFormatException e) {
        logger.info("Empty or failure parsing the cpuList. Defaulting to all available CPUs");
        cpuArrayList.clear();
        break;
      }

      for (int cpu = cpuStart; cpu <= Math.min(maxUsableCpu, cpuEnd); cpu++) {
        addCpuToList.accept(cpu);
      }
    }

    if (cpuArrayList.isEmpty()) {
      for (int cpu = 0; cpu <= maxUsableCpu; cpu++) {
        addCpuToList.accept(cpu);
      }
    }

    logger.info("Using cpuList: {}", cpuArrayList);
  }

  private int getNextCpuVector() {
    return cpuArrayList.get(cpuIndex++ % cpuArrayList.size());
  }

  public RdmaBufferManager getRdmaBufferManager() { return rdmaBufferManager; }

  /**
   * 发送RegionToke认证书给远程Remote RDMANode
   * @param regionToken 内存区域认证书
   */
  public void sendRegionTokenToRemote(RdmaChannel rdmaChannel,RegionToken regionToken) throws IOException, InterruptedException {
    RdmaBuffer rdmaSend = rdmaBufferManager.get(1024);
    ByteBuffer sendBuffer = rdmaSend.getByteBuffer();
    sendBuffer.putInt(regionToken.getSizeInBytes());
    sendBuffer.putLong(regionToken.getAddress());
    sendBuffer.putInt(regionToken.getLocalKey());
    sendBuffer.putInt(regionToken.getRemoteKey());
    CountDownLatch countDownLatch=new CountDownLatch(1);

    rdmaChannel.rdmaSendInQueue(new RdmaCompletionListener() {
      @Override
      public void onSuccess(ByteBuffer buf, Integer IMM) {
        System.out.println("SEND Success!!!");
          countDownLatch.countDown();
      }

      @Override
      public void onFailure(Throwable exception) {

      }
    },new long[]{rdmaSend.getAddress()},new int[]{rdmaSend.getLength()},new int[]{rdmaSend.getLkey()});

    countDownLatch.await();
  }

  public RegionToken getRemoteRegionToken(RdmaChannel rdmaChannel) throws Exception {
    RdmaBuffer rdmaBuffer = rdmaBufferManager.get(1024);
    ByteBuffer byteBuffer = rdmaBuffer.getByteBuffer();

    CountDownLatch countDownLatch=new CountDownLatch(1);

    rdmaChannel.rdmaReceiveInQueue(new RdmaCompletionListener() {
      @Override
      public void onSuccess(ByteBuffer buf, Integer IMM) {
        logger.info("success excute receive request!");
        countDownLatch.countDown();
      }

      @Override
      public void onFailure(Throwable exception) {

      }
    },rdmaBuffer.getAddress(),rdmaBuffer.getLength(),rdmaBuffer.getLkey());

    countDownLatch.await();
    int sizeInBytes=byteBuffer.getInt();
    long address = byteBuffer.getLong();
    int localKey = byteBuffer.getInt();
    int remoteKey = byteBuffer.getInt();
    return new RegionToken(sizeInBytes,address,localKey,remoteKey);
  }

  public RdmaChannel getRdmaChannel(InetSocketAddress remoteAddr, boolean mustRetry,
                                    RdmaChannel.RdmaChannelType rdmaChannelType)
      throws IOException, InterruptedException {
    final long startTime = System.nanoTime();
    final int maxConnectionAttempts = conf.maxConnectionAttempts();
    final long connectionTimeout = maxConnectionAttempts * conf.rdmaCmEventTimeout();
    long elapsedTime = 0;
    int connectionAttempts = 0;

    RdmaChannel rdmaChannel;
    do {
      rdmaChannel = activeRdmaChannelMap.get(remoteAddr);
      if (rdmaChannel == null) {
        rdmaChannel = new RdmaChannel(rdmaChannelType, conf, rdmaBufferManager,
                getNextCpuVector());

        RdmaChannel actualRdmaChannel = activeRdmaChannelMap.putIfAbsent(remoteAddr, rdmaChannel);
        if (actualRdmaChannel != null) {
          rdmaChannel = actualRdmaChannel;
        } else {
          try {
            rdmaChannel.connect(remoteAddr);
            logger.info("Established connection to " + remoteAddr + " in " +
                    (System.nanoTime() - startTime) / 1000000 + " ms");
          } catch (IOException e) {
            ++connectionAttempts;
            activeRdmaChannelMap.remove(remoteAddr, rdmaChannel);
            rdmaChannel.stop();
            if (mustRetry) {
              if (connectionAttempts == maxConnectionAttempts) {
                logger.error("Failed to connect to " + remoteAddr + " after " +
                        maxConnectionAttempts + " attempts, aborting");
                throw e;
              } else {
                logger.error("Failed to connect to " + remoteAddr + ", attempt " +
                        connectionAttempts + " of " + maxConnectionAttempts + " with exception: " + e);
                continue;
              }
            } else {
              logger.error("Failed to connect to " + remoteAddr + " with exception: " + e);
            }
          }
        }
      }

      if (rdmaChannel.isError()) {
        activeRdmaChannelMap.remove(remoteAddr, rdmaChannel);
        rdmaChannel.stop();
        continue;
      }

      if (!rdmaChannel.isConnected()) {
        rdmaChannel.waitForActiveConnection();
        elapsedTime = (System.nanoTime() - startTime) / 1000000;
      }

      if (rdmaChannel.isConnected()) {
        break;
      }
    } while (mustRetry && (connectionTimeout - elapsedTime) > 0);

    if (mustRetry && !rdmaChannel.isConnected()) {
      throw new IOException("Timeout in establishing a connection to " + remoteAddr.toString());
    }

    return rdmaChannel;
  }

  private FutureTask<Void> createFutureChannelStopTask(final RdmaChannel rdmaChannel) {
    FutureTask<Void> futureTask = new FutureTask<>(() -> {
      try {
        rdmaChannel.stop();
      } catch (InterruptedException | IOException e) {
        logger.warn("Exception caught while stopping an RdmaChannel", e);
      }
    }, null);

    // TODO: Use our own ExecutorService in Java
    ExecutorsServiceContext.getInstance().execute(futureTask);
    return futureTask;
  }

  public void stop() throws Exception {
    // Spawn simultaneous disconnect tasks to speed up tear-down
    LinkedList<FutureTask<Void>> futureTaskList = new LinkedList<>();
    logger.info("activeRdmaChannelMap"+activeRdmaChannelMap);
    logger.info("passiveRdmaChannelMap"+passiveRdmaChannelMap);
    for (InetSocketAddress inetSocketAddress: activeRdmaChannelMap.keySet()) {
      final RdmaChannel rdmaChannel = activeRdmaChannelMap.remove(inetSocketAddress);
      futureTaskList.add(createFutureChannelStopTask(rdmaChannel));
    }

    // Wait for all of the channels to disconnect
    for (FutureTask<Void> futureTask: futureTaskList) { futureTask.get(); }

    if (runThread.getAndSet(false)) { listeningThread.join(); }

    // Spawn simultaneous disconnect tasks to speed up tear-down
    futureTaskList = new LinkedList<>();
    for (InetSocketAddress inetSocketAddress: passiveRdmaChannelMap.keySet()) {
      final RdmaChannel rdmaChannel = passiveRdmaChannelMap.remove(inetSocketAddress);
      futureTaskList.add(createFutureChannelStopTask(rdmaChannel));
    }

    for (String hostname : passiveRdmaInetSocketMap.keySet()) {
      passiveRdmaInetSocketMap.remove(hostname);
    }

    // Wait for all of the channels to disconnect
    for (FutureTask<Void> futureTask: futureTaskList) { futureTask.get(); }

    if (rdmaBufferManager != null) { rdmaBufferManager.stop(); }
    if (ibvPd != null) { ibvPd.deallocPd(); }
    if (listenerRdmaCmId != null) { listenerRdmaCmId.destroyId(); }
    if (cmChannel != null) { cmChannel.destroyEventChannel(); }
  }

  public InetSocketAddress getLocalInetSocketAddress() { return localInetSocketAddress; }

}
