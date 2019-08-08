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

package com.netflix.eureka.resources;

import javax.ws.rs.core.Response;

import com.netflix.appinfo.EurekaAccept;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.Version;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.ResponseCache;
import com.netflix.eureka.registry.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract class for the common functionality of a VIP/SVIP resource.
 *
 * 作为 Vip 相关的 接口的 基类
 * @author Nitesh Kant (nkant@netflix.com)
 */
abstract class AbstractVIPResource {

    private static final Logger logger = LoggerFactory.getLogger(AbstractVIPResource.class);

    /**
     * 内部维护了 注册中心
     */
    private final PeerAwareInstanceRegistry registry;
    /**
     * 内部维护了缓存对象
     */
    private final ResponseCache responseCache;

    /**
     * 因为在启动时 在EurekaServerContextHolder 的静态变量中 已经安置了 一个 上下文对象 内部包含了注册中心 本机服务实例 等信息
     * @param server
     */
    AbstractVIPResource(EurekaServerContext server) {
        this.registry = server.getRegistry();
        this.responseCache = registry.getResponseCache();
    }

    AbstractVIPResource() {
        this(EurekaServerContextHolder.getInstance().getServerContext());
    }

    /**
     * 获取vip 资源
     * @param version 尝试获取的实例版本信息
     * @param entityName 其实就是 vipAddress 代表对应的 serviceUrl 获取对应的 apps
     * @param acceptHeader 请求头 对应 application/json  代表期待接受json 数据
     * @param eurekaAccept 代表需要接受的是 压缩数据还是非压缩
     * @param entityType
     * @return
     */
    protected Response getVipResponse(String version, String entityName, String acceptHeader,
                                      EurekaAccept eurekaAccept, Key.EntityType entityType) {
        // 如果注册中心不允许 访问 则返回 403 这里默认情况是不需要获取 remoteRegion 下的数据的
        // 一旦监听到 servletContext 初始化完成后  就会开始拉取 其他zone 的数据并填写到本地的register 这时shouldAllowAccess就会为true
        if (!registry.shouldAllowAccess(false)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        // 设置本次请求的 version  (有什么含义???)  只有2个版本 V1 ， V2
        CurrentRequestVersion.set(Version.toEnum(version));
        // 默认获取 json
        Key.KeyType keyType = Key.KeyType.JSON;
        // 其余情况 返回XML
        if (acceptHeader == null || !acceptHeader.contains("json")) {
            keyType = Key.KeyType.XML;
        }

        // 使用这些特殊信息 生成 缓存键
        Key cacheKey = new Key(
                entityType,
                entityName,
                keyType,
                CurrentRequestVersion.get(),
                eurekaAccept
        );

        // 首先尝试从缓存中获取数据 这里会先从二级缓存中获取 (同时二级缓存会 在定时任务的处理下 同步到 一级缓存)
        String payLoad = responseCache.get(cacheKey);

        if (payLoad != null) {
            logger.debug("Found: {}", entityName);
            return Response.ok(payLoad).build();
        } else {
            // 未获取到数据 返回 404 这时  在 ribbon 的作用下开启重试机制
            logger.debug("Not Found: {}", entityName);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }
}
