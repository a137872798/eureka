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
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;

/**
 * eureka 注册中心的 上下文对象 应该是对应整个注册中心生命周期的
 * @author David Liu
 */
public interface EurekaServerContext {

    /**
     * 对下面管理的组件进行统一初始化
     * @throws Exception
     */
    void initialize() throws Exception;

    /**
     * 关闭注册中心
     * @throws Exception
     */
    void shutdown() throws Exception;

    /**
     * 获取注册中心服务端配置
     * @return
     */
    EurekaServerConfig getServerConfig();

    /**
     * 获取同级的 eureka 节点对象
     * @return
     */
    PeerEurekaNodes getPeerEurekaNodes();

    /**
     * 获取服务的编解码器
     * @return
     */
    ServerCodecs getServerCodecs();

    /**
     * 获取注册中心
     * @return
     */
    PeerAwareInstanceRegistry getRegistry();

    /**
     * 应用信息管理器
     * @return
     */
    ApplicationInfoManager getApplicationInfoManager();

}
