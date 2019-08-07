/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.transport.decorator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.discovery.shared.resolver.ClusterResolver;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpClientFactory;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.EurekaTransportConfig;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.TransportException;
import com.netflix.discovery.shared.transport.TransportUtils;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.discovery.EurekaClientNames.METRIC_TRANSPORT_PREFIX;

/**
 * {@link RetryableEurekaHttpClient} retries failed requests on subsequent servers in the cluster.
 * It maintains also simple quarantine list, so operations are not retried again on servers
 * that are not reachable at the moment.
 * <h3>Quarantine</h3>
 * All the servers to which communication failed are put on the quarantine list. First successful execution
 * clears this list, which makes those server eligible for serving future requests.
 * The list is also cleared once all available servers are exhausted.
 * <h3>5xx</h3>
 * If 5xx status code is returned, {@link ServerStatusEvaluator} predicate evaluates if the retries should be
 * retried on another server, or the response with this status code returned to the client.
 *
 * @author Tomasz Bak
 * @author Li gang
 * 可重试的client 工厂
 */
public class RetryableEurekaHttpClient extends EurekaHttpClientDecorator {

    private static final Logger logger = LoggerFactory.getLogger(RetryableEurekaHttpClient.class);

    public static final int DEFAULT_NUMBER_OF_RETRIES = 3;

    private final String name;
    /**
     * 连接相关的配置对象 内部还是通过委托给eurekaClientConfig 对象来实现
     */
    private final EurekaTransportConfig transportConfig;
    /**
     * 该对象能将 config 中 zone 信息解析出来并抽象成 endpoint 对象
     */
    private final ClusterResolver clusterResolver;
    /**
     * 连接client工厂
     */
    private final TransportClientFactory clientFactory;
    /**
     * 服务状态评估对象
     */
    private final ServerStatusEvaluator serverStatusEvaluator;
    /**
     * 代表允许的重试次数
     */
    private final int numberOfRetries;

    /**
     * 维护的代理对象
     */
    private final AtomicReference<EurekaHttpClient> delegate = new AtomicReference<>();

    private final Set<EurekaEndpoint> quarantineSet = new ConcurrentSkipListSet<>();

    public RetryableEurekaHttpClient(String name,
                                     EurekaTransportConfig transportConfig,
                                     ClusterResolver clusterResolver,
                                     TransportClientFactory clientFactory,
                                     ServerStatusEvaluator serverStatusEvaluator,
                                     int numberOfRetries) {
        this.name = name;
        this.transportConfig = transportConfig;
        this.clusterResolver = clusterResolver;
        this.clientFactory = clientFactory;
        this.serverStatusEvaluator = serverStatusEvaluator;
        this.numberOfRetries = numberOfRetries;
        Monitors.registerObject(name, this);
    }

    /**
     * 停止 client
     */
    @Override
    public void shutdown() {
        TransportUtils.shutdown(delegate.get());
        if(Monitors.isObjectRegistered(name, this)) {
            Monitors.unregisterObject(name, this);
        }
    }

    /**
     * 增加 重试的逻辑
     * @param requestExecutor
     * @param <R>
     * @return
     */
    @Override
    protected <R> EurekaHttpResponse<R> execute(RequestExecutor<R> requestExecutor) {
        List<EurekaEndpoint> candidateHosts = null;
        int endpointIdx = 0;
        for (int retry = 0; retry < numberOfRetries; retry++) {
            EurekaHttpClient currentHttpClient = delegate.get();
            EurekaEndpoint currentEndpoint = null;
            if (currentHttpClient == null) {
                // 先获取 代表注册中心的client 对象 一个endpoint 能生成一个 HttpClient 而一个HttpClient 具备与对应注册中心 通信的能力
                if (candidateHosts == null) {
                    candidateHosts = getHostCandidates();
                    if (candidateHosts.isEmpty()) {
                        throw new TransportException("There is no known eureka server; cluster server list is empty");
                    }
                }
                if (endpointIdx >= candidateHosts.size()) {
                    throw new TransportException("Cannot execute request on any known server");
                }

                // 获取 本次被选中的 endpoint 对象
                currentEndpoint = candidateHosts.get(endpointIdx++);
                // 这里就是利用 endpoint 的信息生成一个 client 对象
                currentHttpClient = clientFactory.newClient(currentEndpoint);
            }

            try {
                // 请求client 并返回响应结果
                EurekaHttpResponse<R> response = requestExecutor.execute(currentHttpClient);
                // 结果是否正常
                if (serverStatusEvaluator.accept(response.getStatusCode(), requestExecutor.getRequestType())) {
                    // 正常情况下设置 client 否则会 尝试使用其他client
                    delegate.set(currentHttpClient);
                    if (retry > 0) {
                        logger.info("Request execution succeeded on retry #{}", retry);
                    }
                    return response;
                }
                logger.warn("Request execution failure with status code {}; retrying on another server if available", response.getStatusCode());
            } catch (Exception e) {
                logger.warn("Request execution failed with message: {}", e.getMessage());  // just log message as the underlying client should log the stacktrace
            }

            // Connection error or 5xx from the server that must be retried on another server
            // 非正常情况 将 client 对象设置为null
            delegate.compareAndSet(currentHttpClient, null);
            if (currentEndpoint != null) {
                // 将有问题的 endpoint 信息保存到quarantineSet
                quarantineSet.add(currentEndpoint);
            }
        }
        // 超过重试次数
        throw new TransportException("Retry limit reached; giving up on completing the request");
    }

    public static EurekaHttpClientFactory createFactory(final String name,
                                                        final EurekaTransportConfig transportConfig,
                                                        final ClusterResolver<EurekaEndpoint> clusterResolver,
                                                        final TransportClientFactory delegateFactory,
                                                        final ServerStatusEvaluator serverStatusEvaluator) {
        return new EurekaHttpClientFactory() {
            @Override
            public EurekaHttpClient newClient() {
                return new RetryableEurekaHttpClient(name, transportConfig, clusterResolver, delegateFactory,
                        // 默认重试次数为3次
                        serverStatusEvaluator, DEFAULT_NUMBER_OF_RETRIES);
            }

            @Override
            public void shutdown() {
                delegateFactory.shutdown();
            }
        };
    }

    /**
     * 获取 候选的 endpoint
     * @return
     */
    private List<EurekaEndpoint> getHostCandidates() {
        // 从配置文件上 解析所有可用的 endpoint 对象  这里会打乱 非本 zone 的 endpoint 的顺序
        List<EurekaEndpoint> candidateHosts = clusterResolver.getClusterEndpoints();
        // 只保留交集 该值是可能比 threshold 小的  这个容器中只保留了异常 endpoint 对象
        quarantineSet.retainAll(candidateHosts);

        // If enough hosts are bad, we have no choice but start over again
        // transportConfig.getRetryableClientQuarantineRefreshPercentage() 默认值为 0.66
        int threshold = (int) (candidateHosts.size() * transportConfig.getRetryableClientQuarantineRefreshPercentage());
        //Prevent threshold is too large
        if (threshold > candidateHosts.size()) {
            threshold = candidateHosts.size();
        }
        if (quarantineSet.isEmpty()) {
            // no-op
            // 这里应该是 异常的endpoint 太多了 没办法即使失败也只能继续调用
        } else if (quarantineSet.size() >= threshold) {
            logger.debug("Clearing quarantined list of size {}", quarantineSet.size());
            quarantineSet.clear();
        } else {
            List<EurekaEndpoint> remainingHosts = new ArrayList<>(candidateHosts.size());
            for (EurekaEndpoint endpoint : candidateHosts) {
                // 正常情况就会将 不包含在异常容器中的endpoint 设置到 candidateHost中
                if (!quarantineSet.contains(endpoint)) {
                    remainingHosts.add(endpoint);
                }
            }
            candidateHosts = remainingHosts;
        }

        return candidateHosts;
    }

    @Monitor(name = METRIC_TRANSPORT_PREFIX + "quarantineSize",
            description = "number of servers quarantined", type = DataSourceType.GAUGE)
    public long getQuarantineSetSize() {
        return quarantineSet.size();
    }
}
