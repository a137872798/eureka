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

package com.netflix.eureka.registry;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.resources.ASGResource;

import java.util.List;

/**
 * 增加了 一些 是否允许被对端感知的 接口   Peer 在 eureka 中的概念等同于server 集群  当某个注册中心收到一个client的操作(比如心跳) 就会同步到其他同级的 server
 * @author Tomasz Bak
 */
public interface PeerAwareInstanceRegistry extends InstanceRegistry {

    /**
     * 通过节点列表来初始化 对端信息
     * @param peerEurekaNodes
     * @throws Exception
     */
    void init(PeerEurekaNodes peerEurekaNodes) throws Exception;

    /**
     * Populates the registry information from a peer eureka node. This
     * operation fails over to other nodes until the list is exhausted if the
     * communication fails.
     * 将对端节点的信息 填充到注册表中 如果失败就 轮询到下个节点 直到所有节点都失败
     */
    int syncUp();

    /**
     * Checks to see if the registry access is allowed or the server is in a
     * situation where it does not all getting registry information. The server
     * does not return registry information for a period specified in
     * {@link com.netflix.eureka.EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}, if it cannot
     * get the registry information from the peer eureka nodes at start up.
     *
     * @return false - if the instances count from a replica transfer returned
     *         zero and if the wait time has not elapsed, otherwise returns true
     *         该注册中心当前是否允许访问
     */
     boolean shouldAllowAccess(boolean remoteRegionRequired);

    /**
     * 将服务实例信息 注册到注册中心
     * @param info
     * @param isReplication  是否复制
     */
     void register(InstanceInfo info, boolean isReplication);

    /**
     * 更新状态
     * @param asgName
     * @param newStatus
     * @param isReplication
     */
     void statusUpdate(final String asgName, final ASGResource.ASGStatus newStatus, final boolean isReplication);
}
