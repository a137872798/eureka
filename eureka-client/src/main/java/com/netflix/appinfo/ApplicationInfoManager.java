/*
 * Copyright 2012 Netflix, Inc.
 *f
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

package com.netflix.appinfo;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.StatusChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that initializes information required for registration with
 * <tt>Eureka Server</tt> and to be discovered by other components.
 *
 * <p>
 * The information required for registration is provided by the user by passing
 * the configuration defined by the contract in {@link EurekaInstanceConfig}
 * }.AWS clients can either use or extend {@link CloudInstanceConfig
 * }. Other non-AWS clients can use or extend either
 * {@link MyDataCenterInstanceConfig} or very basic
 * {@link AbstractInstanceConfig}.
 * </p>
 *
 *
 * @author Karthik Ranganathan, Greg Kim
 * 该对象用于 操作内部的 InstanceInfo （该对象代表一个client  实例 通过抽取 eureka-client 文件生成属性）
 */
@Singleton
public class ApplicationInfoManager {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationInfoManager.class);

    /**
     * 默认情况下 status 不会被映射成 其他的 status
     */
    private static final InstanceStatusMapper NO_OP_MAPPER = new InstanceStatusMapper() {
        @Override
        public InstanceStatus map(InstanceStatus prev) {
            return prev;
        }
    };

    private static ApplicationInfoManager instance = new ApplicationInfoManager(null, null, null);

    protected final Map<String, StatusChangeListener> listeners;
    /**
     * 状态映射对象
     */
    private final InstanceStatusMapper instanceStatusMapper;

    /**
     * 实例信息
     */
    private InstanceInfo instanceInfo;
    /**
     * 实例配置
     */
    private EurekaInstanceConfig config;

    public static class OptionalArgs {
        private InstanceStatusMapper instanceStatusMapper;

        @com.google.inject.Inject(optional = true)
        public void setInstanceStatusMapper(InstanceStatusMapper instanceStatusMapper) {
            this.instanceStatusMapper = instanceStatusMapper;
        }

        InstanceStatusMapper getInstanceStatusMapper() {
            return instanceStatusMapper == null ? NO_OP_MAPPER : instanceStatusMapper;
        }
    }

    /**
     * public for DI use. This class should be in singleton scope so do not create explicitly.
     * Either use DI or create this explicitly using one of the other public constructors.
     */
    @Inject
    public ApplicationInfoManager(EurekaInstanceConfig config, InstanceInfo instanceInfo, OptionalArgs optionalArgs) {
        this.config = config;
        this.instanceInfo = instanceInfo;
        this.listeners = new ConcurrentHashMap<String, StatusChangeListener>();
        if (optionalArgs != null) {
            this.instanceStatusMapper = optionalArgs.getInstanceStatusMapper();
        } else {
            this.instanceStatusMapper = NO_OP_MAPPER;
        }

        // Hack to allow for getInstance() to use the DI'd ApplicationInfoManager
        instance = this;
    }

    public ApplicationInfoManager(EurekaInstanceConfig config, /* nullable */ OptionalArgs optionalArgs) {
        this(config, new EurekaConfigBasedInstanceInfoProvider(config).get(), optionalArgs);
    }

    public ApplicationInfoManager(EurekaInstanceConfig config, InstanceInfo instanceInfo) {
        this(config, instanceInfo, null);
    }

    /**
     * @deprecated 2016-09-19 prefer {@link #ApplicationInfoManager(EurekaInstanceConfig, com.netflix.appinfo.ApplicationInfoManager.OptionalArgs)}
     */
    @Deprecated
    public ApplicationInfoManager(EurekaInstanceConfig config) {
        this(config, (OptionalArgs) null);
    }

    /**
     * @deprecated please use DI instead
     */
    @Deprecated
    public static ApplicationInfoManager getInstance() {
        return instance;
    }

    /**
     * 使用配置对象初始化组件
     * @param config
     */
    public void initComponent(EurekaInstanceConfig config) {
        try {
            this.config = config;
            this.instanceInfo = new EurekaConfigBasedInstanceInfoProvider(config).get();
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize ApplicationInfoManager", e);
        }
    }

    /**
     * Gets the information about this instance that is registered with eureka.
     *
     * @return information about this instance that is registered with eureka.
     */
    public InstanceInfo getInfo() {
        return instanceInfo;
    }

    public EurekaInstanceConfig getEurekaInstanceConfig() {
        return config;
    }

    /**
     * Register user-specific instance meta data. Application can send any other
     * additional meta data that need to be accessed for other reasons.The data
     * will be periodically sent to the eureka server.
     *
     * Please Note that metadata added via this method is not guaranteed to be submitted
     * to the eureka servers upon initial registration, and may be submitted as an update
     * at a subsequent time. If you want guaranteed metadata for initial registration,
     * please use the mechanism described in {@link EurekaInstanceConfig#getMetadataMap()}
     *
     * @param appMetadata application specific meta data.
     *                    将元数据设置到instanceInfo
     */
    public void registerAppMetadata(Map<String, String> appMetadata) {
        instanceInfo.registerRuntimeMetadata(appMetadata);
    }

    /**
     * Set the status of this instance. Application can use this to indicate
     * whether it is ready to receive traffic. Setting the status here also notifies all registered listeners
     * of a status change event.
     *
     * @param status Status of the instance
     *               更改状态 并通知所有监听器
     */
    public synchronized void setInstanceStatus(InstanceStatus status) {
        InstanceStatus next = instanceStatusMapper.map(status);
        if (next == null) {
            return;
        }

        InstanceStatus prev = instanceInfo.setStatus(next);
        if (prev != null) {
            for (StatusChangeListener listener : listeners.values()) {
                try {
                    listener.notify(new StatusChangeEvent(prev, next));
                } catch (Exception e) {
                    logger.warn("failed to notify listener: {}", listener.getId(), e);
                }
            }
        }
    }

    public void registerStatusChangeListener(StatusChangeListener listener) {
        listeners.put(listener.getId(), listener);
    }

    public void unregisterStatusChangeListener(String listenerId) {
        listeners.remove(listenerId);
    }

    /**
     * Refetches the hostname to check if it has changed. If it has, the entire
     * <code>DataCenterInfo</code> is refetched and passed on to the eureka
     * server on next heartbeat.
     *
     * see {@link InstanceInfo#getHostName()} for explanation on why the hostname is used as the default address
     *      更新当前主机信息 如果是基于本地配置 不会发生变化 否则会从 类似于 配置中心的地方 定期获取最新配置
     */
    public void refreshDataCenterInfoIfRequired() {
        // 获取应用实例的 地址 准备更新信息
        String existingAddress = instanceInfo.getHostName();

        String existingSpotInstanceAction = null;
        //有关 amazon 的先不管
        if (instanceInfo.getDataCenterInfo() instanceof AmazonInfo) {
            existingSpotInstanceAction = ((AmazonInfo) instanceInfo.getDataCenterInfo()).get(AmazonInfo.MetaDataKey.spotInstanceAction);
        }

        String newAddress;
        if (config instanceof RefreshableInstanceConfig) {
            // Refresh data center info, and return up to date address
            // 获取最新的 地址
            newAddress = ((RefreshableInstanceConfig) config).resolveDefaultAddress(true);
        } else {
            newAddress = config.getHostName(true);
        }
        String newIp = config.getIpAddress();

        if (newAddress != null && !newAddress.equals(existingAddress)) {
            logger.warn("The address changed from : {} => {}", existingAddress, newAddress);
            updateInstanceInfo(newAddress, newIp);
        }

        if (config.getDataCenterInfo() instanceof AmazonInfo) {
            String newSpotInstanceAction = ((AmazonInfo) config.getDataCenterInfo()).get(AmazonInfo.MetaDataKey.spotInstanceAction);
            if (newSpotInstanceAction != null && !newSpotInstanceAction.equals(existingSpotInstanceAction)) {
                logger.info(String.format("The spot instance termination action changed from: %s => %s",
                        existingSpotInstanceAction,
                        newSpotInstanceAction));
                updateInstanceInfo(null , null );
            }
        }        
    }

    private void updateInstanceInfo(String newAddress, String newIp) {
        // :( in the legacy code here the builder is acting as a mutator.
        // This is hard to fix as this same instanceInfo instance is referenced elsewhere.
        // We will most likely re-write the client at sometime so not fixing for now.
        InstanceInfo.Builder builder = new InstanceInfo.Builder(instanceInfo);
        if (newAddress != null) {
            builder.setHostName(newAddress);
        }
        if (newIp != null) {
            builder.setIPAddr(newIp);
        }
        builder.setDataCenterInfo(config.getDataCenterInfo());
        instanceInfo.setIsDirty();
    }

    /**
     * 更新租约信息
     */
    public void refreshLeaseInfoIfRequired() {
        //获取租约的信息
        LeaseInfo leaseInfo = instanceInfo.getLeaseInfo();
        if (leaseInfo == null) {
            return;
        }
        //获取 租约的超时时间 以及新的 间隔时间
        int currentLeaseDuration = config.getLeaseExpirationDurationInSeconds();
        int currentLeaseRenewal = config.getLeaseRenewalIntervalInSeconds();
        //一旦有关 租约的配置信息发生了变化 就生成一个新的 LeaseInfo  那么 热部署就是这么实现的 定期会去读取自身的配置信息 一旦发现修改就同步到 订阅自己的各个注册中心上
        //而 配置信息 又是由配置中心统一管理
        if (leaseInfo.getDurationInSecs() != currentLeaseDuration || leaseInfo.getRenewalIntervalInSecs() != currentLeaseRenewal) {
            LeaseInfo newLeaseInfo = LeaseInfo.Builder.newBuilder()
                    .setRenewalIntervalInSecs(currentLeaseRenewal)
                    .setDurationInSecs(currentLeaseDuration)
                    .build();
            instanceInfo.setLeaseInfo(newLeaseInfo);
            //设置成dirty 可能是 立即触发心跳之类的
            instanceInfo.setIsDirty();
        }
    }

    /**
     * 监听器接口
     */
    public static interface StatusChangeListener {
        /**
         * 获取该监听器的 id
         * @return
         */
        String getId();

        /**
         * 根据事件类型走不同的逻辑
         * @param statusChangeEvent
         */
        void notify(StatusChangeEvent statusChangeEvent);
    }

    /**
     * 实例状态映射
     */
    public static interface InstanceStatusMapper {

        /**
         * given a starting {@link com.netflix.appinfo.InstanceInfo.InstanceStatus}, apply a mapping to return
         * the follow up status, if applicable.
         *
         * @return the mapped instance status, or null if the mapping is not applicable.
         * 将传入的 status 映射成 另一个 status
         */
        InstanceStatus map(InstanceStatus prev);
    }

}
