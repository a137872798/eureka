/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.eureka;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.discovery.DiscoveryManager;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.netflix.eureka.util.ServoControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Represent the local server context and exposes getters to components of the
 * local server such as the registry.
 *
 * @author David Liu
 * 该对象负责管理一系列 eureka组件
 */
@Singleton
public class DefaultEurekaServerContext implements EurekaServerContext {
    private static final Logger logger = LoggerFactory.getLogger(DefaultEurekaServerContext.class);

    /**
     * 本机作为注册中心的配置
     */
    private final EurekaServerConfig serverConfig;
    /**
     * 编解码器
     */
    private final ServerCodecs serverCodecs;
    /**
     * 该对象内部维护一个 nodes 之后对该 registry 发送请求时 会被转发给所有的 node
     */
    private final PeerAwareInstanceRegistry registry;
    /**
     * 通过解析配置文件中本 region 下第一个 zone 对应的 serviceUrl 来生成 nodes
     */
    private final PeerEurekaNodes peerEurekaNodes;
    /**
     * 实例管理对象   内部的instance 是本节点对应的实例
     */
    private final ApplicationInfoManager applicationInfoManager;

    @Inject
    public DefaultEurekaServerContext(EurekaServerConfig serverConfig,
                               ServerCodecs serverCodecs,
                               PeerAwareInstanceRegistry registry,
                               PeerEurekaNodes peerEurekaNodes,
                               ApplicationInfoManager applicationInfoManager) {
        this.serverConfig = serverConfig;
        this.serverCodecs = serverCodecs;
        this.registry = registry;
        this.peerEurekaNodes = peerEurekaNodes;
        this.applicationInfoManager = applicationInfoManager;
    }

    @PostConstruct
    @Override
    public void initialize() {
        logger.info("Initializing ...");
        // nodes.start() 就是从配置文件中定期拉取本region下所有zone 并存储到容器中  (同时将zone 转换成node 对象)
        peerEurekaNodes.start();
        try {
            // 使用生成的 nodes 对象去初始化 eurekaServer 对象   这样当往本节点注册中心发送请求时 就会同步到其他节点
            // 同时从配置文件中获取 其他region下的注册中心 并生成client 用于访问  当本集群找不到某个应用时根据配置允许往其他region读取
            registry.init(peerEurekaNodes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        logger.info("Initialized");
    }

    @PreDestroy
    @Override
    public void shutdown() {
        logger.info("Shutting down ...");
        registry.shutdown();
        peerEurekaNodes.shutdown();
        ServoControl.shutdown();
        EurekaMonitors.shutdown();
        logger.info("Shut down");
    }

    @Override
    public EurekaServerConfig getServerConfig() {
        return serverConfig;
    }

    @Override
    public PeerEurekaNodes getPeerEurekaNodes() {
        return peerEurekaNodes;
    }

    @Override
    public ServerCodecs getServerCodecs() {
        return serverCodecs;
    }

    @Override
    public PeerAwareInstanceRegistry getRegistry() {
        return registry;
    }

    @Override
    public ApplicationInfoManager getApplicationInfoManager() {
        return applicationInfoManager;
    }

}
