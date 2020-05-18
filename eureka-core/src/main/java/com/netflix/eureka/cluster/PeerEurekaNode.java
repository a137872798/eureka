/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.cluster;

import java.net.MalformedURLException;
import java.net.URL;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl.Action;
import com.netflix.eureka.resources.ASGResource.ASGStatus;
import com.netflix.eureka.util.batcher.TaskDispatcher;
import com.netflix.eureka.util.batcher.TaskDispatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>PeerEurekaNode</code> represents a peer node to which information
 * should be shared from this node.
 *
 * <p>
 * This class handles replicating all update operations like
 * <em>Register,Renew,Cancel,Expiration and Status Changes</em> to the eureka
 * node it represents.
 * <p>
 *
 * @author Karthik Ranganathan, Greg Kim
 * 代表eureka-server 集群中的某个节点
 */
public class PeerEurekaNode {

    /**
     * A time to wait before continuing work if there is network level error.
     * 网络方面异常 重试时间 为 100毫秒
     */
    private static final long RETRY_SLEEP_TIME_MS = 100;

    /**
     * A time to wait before continuing work if there is congestion on the server side.
     * 服务端出错 选择 等待1秒
     */
    private static final long SERVER_UNAVAILABLE_SLEEP_TIME_MS = 1000;

    /**
     * Maximum amount of time in ms to wait for new items prior to dispatching a batch of tasks.
     * 执行批量任务 要等待 0.5秒
     */
    private static final long MAX_BATCHING_DELAY_MS = 500;

    /**
     * Maximum batch size for batched requests.
     * 最多允许一次发送250 个请求
     */
    private static final int BATCH_SIZE = 250;

    private static final Logger logger = LoggerFactory.getLogger(PeerEurekaNode.class);

    /**
     * 代表使用 复制操作时访问的 url
     */
    public static final String BATCH_URL_PATH = "peerreplication/batch/";

    /**
     * 代表本次请求 是 复制操作的 请求头
     */
    public static final String HEADER_REPLICATION = "x-netflix-discovery-replication";

    /**
     * 该节点 对应的url
     */
    private final String serviceUrl;
    /**
     * 服务器配置对象  就代表该节点 描述的是一个 注册中心对象
     */
    private final EurekaServerConfig config;

    private final long maxProcessingDelayMs;
    /**
     * 注册中心   该对象被所有node 共享
     */
    private final PeerAwareInstanceRegistry registry;
    /**
     * 目标主机
     */
    private final String targetHost;

    private final HttpReplicationClient replicationClient;

    /**
     * 批量任务处理器
     */
    private final TaskDispatcher<String, ReplicationTask> batchingDispatcher;
    /**
     * 单任务处理器
     */
    private final TaskDispatcher<String, ReplicationTask> nonBatchingDispatcher;

    public PeerEurekaNode(PeerAwareInstanceRegistry registry, String targetHost, String serviceUrl, HttpReplicationClient replicationClient, EurekaServerConfig config) {
        this(registry, targetHost, serviceUrl, replicationClient, config, BATCH_SIZE, MAX_BATCHING_DELAY_MS, RETRY_SLEEP_TIME_MS, SERVER_UNAVAILABLE_SLEEP_TIME_MS);
    }

    /* For testing */ PeerEurekaNode(PeerAwareInstanceRegistry registry, String targetHost, String serviceUrl,
                                     HttpReplicationClient replicationClient, EurekaServerConfig config,
                                     int batchSize, long maxBatchingDelayMs,
                                     long retrySleepTimeMs, long serverUnavailableSleepTimeMs) {
        // 某个eureka下所有节点共用一个registry 也就是注册中心 同时该对象的实现本身具备将某一请求发往集群内所有节点的能力
        this.registry = registry;
        // 目标主机
        this.targetHost = targetHost;
        // 就是能够发http请求的普通对象
        this.replicationClient = replicationClient;

        // 主机url
        this.serviceUrl = serviceUrl;
        this.config = config;
        this.maxProcessingDelayMs = config.getMaxTimeForReplication();

        String batcherName = getBatcherName();
        // 这里将client 包装成一个processor对象 也就是处理 replication任务 就是通过client向目标地址发送请求
        ReplicationTaskProcessor taskProcessor = new ReplicationTaskProcessor(targetHost, replicationClient);
        // 创建一个 批量处理任务的对象
        this.batchingDispatcher = TaskDispatchers.createBatchingTaskDispatcher(
                batcherName,
                config.getMaxElementsInPeerReplicationPool(),
                batchSize,
                config.getMaxThreadsForPeerReplication(),
                maxBatchingDelayMs,
                serverUnavailableSleepTimeMs,
                retrySleepTimeMs,
                taskProcessor
        );
        // 创建 执行普通任务的对象
        this.nonBatchingDispatcher = TaskDispatchers.createNonBatchingTaskDispatcher(
                targetHost,
                config.getMaxElementsInStatusReplicationPool(),
                config.getMaxThreadsForStatusReplication(),
                maxBatchingDelayMs,
                serverUnavailableSleepTimeMs,
                retrySleepTimeMs,
                taskProcessor
        );
    }

    /**
     * Sends the registration information of {@link InstanceInfo} receiving by
     * this node to the peer node represented by this class.
     *
     * @param info
     *            the instance information {@link InstanceInfo} of any instance
     *            that is send to this instance.
     * @throws Exception
     * 接受到注册任务时 要同步到所有同级节点  这里采用异步处理  使得eureka-client的注册请求能够快速返回 毕竟不同于CP 只要集群中成功注册一个
     * 就认为成功了   eureka-client端如果往某个节点发送失败了  是自带重试机制的
     */
    public void register(final InstanceInfo info) throws Exception {
        // 获取续约时间 毫秒数
        long expiryTime = System.currentTimeMillis() + getLeaseRenewalOf(info);
        batchingDispatcher.process(
                // 生成任务id  该对象具备相同任务id 会 覆盖掉旧任务的特性
                taskId("register", info),
                // replicateInstanceInfo 代表是否要将该 实例信息复制到其他节点
                // lambda中重写的是 execute()
                new InstanceReplicationTask(targetHost, Action.Register, info, null, true) {
                    // 该任务的 核心方法 就是委托给 执行复制任务的client 调用对用的 api
                    // replicationClient 该client的特性就是 在写res时 会追加一个 isReplication = true
                    public EurekaHttpResponse<Void> execute() {
                        return replicationClient.register(info);
                    }
                },
                // 超过了 最大续约时间 就是代表超时
                expiryTime
        );
    }

    /**
     * Send the cancellation information of an instance to the node represented
     * by this class.
     *
     * @param appName
     *            the application name of the instance.
     * @param id
     *            the unique identifier of the instance.
     * @throws Exception
     * 将关闭任务发到同级节点上
     */
    public void cancel(final String appName, final String id) throws Exception {
        long expiryTime = System.currentTimeMillis() + maxProcessingDelayMs;
        batchingDispatcher.process(
                taskId("cancel", appName, id),
                // 复制cancel任务  该请求不需要同步到其他节点
                new InstanceReplicationTask(targetHost, Action.Cancel, appName, id) {
                    /**
                     * 委托执行关闭任务
                     * @return
                     */
                    @Override
                    public EurekaHttpResponse<Void> execute() {
                        return replicationClient.cancel(appName, id);
                    }

                    /**
                     * 失败时 打印日志
                     * @param statusCode
                     * @param responseEntity
                     * @throws Throwable
                     */
                    @Override
                    public void handleFailure(int statusCode, Object responseEntity) throws Throwable {
                        super.handleFailure(statusCode, responseEntity);
                        if (statusCode == 404) {
                            logger.warn("{}: missing entry.", getTaskName());
                        }
                    }
                },
                expiryTime
        );
    }

    /**
     * Send the heartbeat information of an instance to the node represented by
     * this class. If the instance does not exist the node, the instance
     * registration information is sent again to the peer node.
     *
     * @param appName
     *            the application name of the instance.
     * @param id
     *            the unique identifier of the instance.
     * @param info
     *            the instance info {@link InstanceInfo} of the instance.
     * @param overriddenStatus
     *            the overridden status information if any of the instance.
     * @throws Throwable
     * 发送心跳任务
     */
    public void heartbeat(final String appName, final String id,
                          final InstanceInfo info, final InstanceStatus overriddenStatus,
                          boolean primeConnection) throws Throwable {
        // 如果是主连接  直接在本线程内完成任务
        if (primeConnection) {
            // We do not care about the result for priming request.
            replicationClient.sendHeartBeat(appName, id, info, overriddenStatus);
            return;
        }
        // 非主连接的情况 将任务添加到生产者消费者模型中 通过后面的批处理线程池来执行任务   该任务也不需要同步到其他节点
        ReplicationTask replicationTask = new InstanceReplicationTask(targetHost, Action.Heartbeat, info, overriddenStatus, false) {
            @Override
            public EurekaHttpResponse<InstanceInfo> execute() throws Throwable {
                return replicationClient.sendHeartBeat(appName, id, info, overriddenStatus);
            }

            @Override
            public void handleFailure(int statusCode, Object responseEntity) throws Throwable {
                super.handleFailure(statusCode, responseEntity);
                if (statusCode == 404) {
                    logger.warn("{}: missing entry.", getTaskName());
                    if (info != null) {
                        logger.warn("{}: cannot find instance id {} and hence replicating the instance with status {}",
                                getTaskName(), info.getId(), info.getStatus());
                        // 返回404 代表没有在目标节点(eureka-server) 上找到本实例 那么重新注册
                        register(info);
                    }
                } else if (config.shouldSyncWhenTimestampDiffers()) {
                    // 返回的应该时本实例在对端节点的状态
                    InstanceInfo peerInstanceInfo = (InstanceInfo) responseEntity;
                    if (peerInstanceInfo != null) {
                        // 将节点信息注册到注册中心 以及更新状态
                        syncInstancesIfTimestampDiffers(appName, id, info, peerInstanceInfo);
                    }
                }
            }
        };
        // 超过续约时间内认为是任务超时
        long expiryTime = System.currentTimeMillis() + getLeaseRenewalOf(info);
        batchingDispatcher.process(taskId("heartbeat", info), replicationTask, expiryTime);
    }

    /**
     * Send the status information of of the ASG represented by the instance.
     *
     * <p>
     * ASG (Autoscaling group) names are available for instances in AWS and the
     * ASG information is used for determining if the instance should be
     * registered as {@link InstanceStatus#DOWN} or {@link InstanceStatus#UP}.
     *
     * @param asgName
     *            the asg name if any of this instance.
     * @param newStatus
     *            the new status of the ASG.
     *            ASG 的不看
     */
    public void statusUpdate(final String asgName, final ASGStatus newStatus) {
        long expiryTime = System.currentTimeMillis() + maxProcessingDelayMs;
        nonBatchingDispatcher.process(
                asgName,
                new AsgReplicationTask(targetHost, Action.StatusUpdate, asgName, newStatus) {
                    public EurekaHttpResponse<?> execute() {
                        return replicationClient.statusUpdate(asgName, newStatus);
                    }
                },
                expiryTime
        );
    }

    /**
     *
     * Send the status update of the instance.
     *
     * @param appName
     *            the application name of the instance.
     * @param id
     *            the unique identifier of the instance.
     * @param newStatus
     *            the new status of the instance.
     * @param info
     *            the instance information of the instance.
     *            同步节点间 更新状态的请求
     */
    public void statusUpdate(final String appName, final String id,
                             final InstanceStatus newStatus, final InstanceInfo info) {
        long expiryTime = System.currentTimeMillis() + maxProcessingDelayMs;
        batchingDispatcher.process(
                taskId("statusUpdate", appName, id),
                // 修改状态的请求不需要同步到所有节点
                new InstanceReplicationTask(targetHost, Action.StatusUpdate, info, null, false) {
                    @Override
                    public EurekaHttpResponse<Void> execute() {
                        return replicationClient.statusUpdate(appName, id, newStatus, info);
                    }
                },
                expiryTime
        );
    }

    /**
     * Delete instance status override.
     *
     * @param appName
     *            the application name of the instance.
     * @param id
     *            the unique identifier of the instance.
     * @param info
     *            the instance information of the instance.
     *            删除 修改的状态
     */
    public void deleteStatusOverride(final String appName, final String id, final InstanceInfo info) {
        long expiryTime = System.currentTimeMillis() + maxProcessingDelayMs;
        batchingDispatcher.process(
                taskId("deleteStatusOverride", appName, id),
                new InstanceReplicationTask(targetHost, Action.DeleteStatusOverride, info, null, false) {
                    @Override
                    public EurekaHttpResponse<Void> execute() {
                        return replicationClient.deleteStatusOverride(appName, id, info);
                    }
                },
                expiryTime);
    }

    /**
     * Get the service Url of the peer eureka node.
     *
     * @return the service Url of the peer eureka node.
     */
    public String getServiceUrl() {
        return serviceUrl;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((serviceUrl == null) ? 0 : serviceUrl.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PeerEurekaNode other = (PeerEurekaNode) obj;
        if (serviceUrl == null) {
            if (other.serviceUrl != null) {
                return false;
            }
        } else if (!serviceUrl.equals(other.serviceUrl)) {
            return false;
        }
        return true;
    }

    /**
     * Shuts down all resources used for peer replication.
     */
    public void shutDown() {
        batchingDispatcher.shutdown();
        nonBatchingDispatcher.shutdown();
        replicationClient.shutdown();
    }

    /**
     * Synchronize {@link InstanceInfo} information if the timestamp between
     * this node and the peer eureka nodes vary.
     * 同步2个节点间的时间戳
     */
    private void syncInstancesIfTimestampDiffers(String appName, String id, InstanceInfo info, InstanceInfo infoFromPeer  // 本节点在注册中心的信息
    ) {
        try {
            if (infoFromPeer != null) {
                logger.warn("Peer wants us to take the instance information from it, since the timestamp differs,"
                        + "Id : {} My Timestamp : {}, Peer's timestamp: {}", id, info.getLastDirtyTimestamp(), infoFromPeer.getLastDirtyTimestamp());

                // 代表对端节点修改过状态
                if (infoFromPeer.getOverriddenStatus() != null && !InstanceStatus.UNKNOWN.equals(infoFromPeer.getOverriddenStatus())) {
                    logger.warn("Overridden Status info -id {}, mine {}, peer's {}", id, info.getOverriddenStatus(), infoFromPeer.getOverriddenStatus());
                    registry.storeOverriddenStatusIfRequired(appName, id, infoFromPeer.getOverriddenStatus());
                }
                // 将对端返回的节点信息作修改后重新注册回去
                registry.register(infoFromPeer, true);
            }
        } catch (Throwable e) {
            logger.warn("Exception when trying to set information from peer :", e);
        }
    }

    public String getBatcherName() {
        String batcherName;
        try {
            batcherName = new URL(serviceUrl).getHost();
        } catch (MalformedURLException e1) {
            batcherName = serviceUrl;
        }
        return "target_" + batcherName;
    }

    private static String taskId(String requestType, String appName, String id) {
        return requestType + '#' + appName + '/' + id;
    }

    private static String taskId(String requestType, InstanceInfo info) {
        return taskId(requestType, info.getAppName(), info.getId());
    }

    private static int getLeaseRenewalOf(InstanceInfo info) {
        return (info.getLeaseInfo() == null ? Lease.DEFAULT_DURATION_IN_SECS : info.getLeaseInfo().getRenewalIntervalInSecs()) * 1000;
    }
}
