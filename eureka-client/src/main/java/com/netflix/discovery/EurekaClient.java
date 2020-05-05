package com.netflix.discovery;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.inject.ImplementedBy;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.LookupService;

/**
 * Define a simple interface over the current DiscoveryClient implementation.
 *
 * This interface does NOT try to clean up the current client interface for eureka 1.x. Rather it tries
 * to provide an easier transition path from eureka 1.x to eureka 2.x.
 *
 * EurekaClient API contracts are:
 *  - provide the ability to get InstanceInfo(s) (in various different ways)
 *  - provide the ability to get data about the local Client (known regions, own AZ etc)
 *  - provide the ability to register and access the healthcheck handler for the client
 *
 * @author David Liu
 *      eureka是基于client-server 模型的 server负责保存数据 而client负责发起命令
 */
@ImplementedBy(DiscoveryClient.class)
public interface EurekaClient extends LookupService {

    // ========================
    // getters for InstanceInfo
    // ========================

    /**
     * @param region the region that the Applications reside in
     * @return an {@link com.netflix.discovery.shared.Applications} for the matching region. a Null value
     *         is treated as the local region.
     *         获取某个地区下所有服务 以及它们下面的实例信息  通过分区的概念获取最近的服务实例 用于提升性能
     */
    public Applications getApplicationsForARegion(@Nullable String region);

    /**
     * Get all applications registered with a specific eureka service.
     *
     * @param serviceUrl The string representation of the service url.
     * @return The registry information containing all applications.
     *
     *          通过指定某个 eureka-server 的地址 获取提供的所有应用
     */
    public Applications getApplications(String serviceUrl);

    /**
     * Gets the list of instances matching the given VIP Address.
     *
     * @param vipAddress The VIP address to match the instances for.
     * @param secure true if it is a secure vip address, false otherwise
     * @return - The list of {@link InstanceInfo} objects matching the criteria
     *      指定某个给定的 vip地址  获取下面所有实例  （instanceInfo 本身也包含了 application 信息）
     *      也就是某个物理节点如果同时提供了 多个 application  那么会存在多个 application->instanceInfo 的映射关系
     *      同时它们的 id 还是一致的 (推测)
     */
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure);

    /**
     * Gets the list of instances matching the given VIP Address in the passed region.
     *
     * @param vipAddress The VIP address to match the instances for.
     * @param secure true if it is a secure vip address, false otherwise
     * @param region region from which the instances are to be fetched. If <code>null</code> then local region is
     *               assumed.
     *
     * @return - The list of {@link InstanceInfo} objects matching the criteria, empty list if not instances found.
     *      这个vip地址到底指什么 在没有指定application 会返回所有的 实例吗  那么为什么不干脆返回 Applications呢???
     */
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure, @Nullable String region);

    /**
     * Gets the list of instances matching the given VIP Address and the given
     * application name if both of them are not null. If one of them is null,
     * then that criterion is completely ignored for matching instances.
     *
     * @param vipAddress The VIP address to match the instances for.
     * @param appName The applicationName to match the instances for.
     * @param secure true if it is a secure vip address, false otherwise.
     * @return - The list of {@link InstanceInfo} objects matching the criteria.
     *      指定应用名 和vip 地址
     */
    public List<InstanceInfo> getInstancesByVipAddressAndAppName(String vipAddress, String appName, boolean secure);

    // ==========================
    // getters for local metadata
    // ==========================

    /**
     * @return in String form all regions (local + remote) that can be accessed by this client
     *      获取当前所有 region    eureka-server 集群中每个节点的regions 都一样吗 还有 它们本身会在同一个region吗
     */
    public Set<String> getAllKnownRegions();

    /**
     * @return the current self instance status as seen on the Eureka server.
     *     获取当前节点在 eureka-server 的状态
     */
    public InstanceInfo.InstanceStatus getInstanceRemoteStatus();

    /**
     * @deprecated see {@link com.netflix.discovery.endpoint.EndpointUtils} for replacement
     *
     * Get the list of all eureka service urls for the eureka client to talk to.
     *
     * @param zone the zone in which the client resides
     * @return The list of all eureka service urls for the eureka client to talk to.
     */
    @Deprecated
    public List<String> getDiscoveryServiceUrls(String zone);

    /**
     * @deprecated see {@link com.netflix.discovery.endpoint.EndpointUtils} for replacement
     *
     * Get the list of all eureka service urls from properties file for the eureka client to talk to.
     *
     * @param instanceZone The zone in which the client resides
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise
     * @return The list of all eureka service urls for the eureka client to talk to
     */
    @Deprecated
    public List<String> getServiceUrlsFromConfig(String instanceZone, boolean preferSameZone);

    /**
     * @deprecated see {@link com.netflix.discovery.endpoint.EndpointUtils} for replacement
     *
     * Get the list of all eureka service urls from DNS for the eureka client to
     * talk to. The client picks up the service url from its zone and then fails over to
     * other zones randomly. If there are multiple servers in the same zone, the client once
     * again picks one randomly. This way the traffic will be distributed in the case of failures.
     *
     * @param instanceZone The zone in which the client resides.
     * @param preferSameZone true if we have to prefer the same zone as the client, false otherwise.
     * @return The list of all eureka service urls for the eureka client to talk to.
     */
    @Deprecated
    public List<String> getServiceUrlsFromDNS(String instanceZone, boolean preferSameZone);

    // ===========================
    // healthcheck related methods
    // ===========================

    /**
     * @deprecated Use {@link #registerHealthCheck(com.netflix.appinfo.HealthCheckHandler)} instead.
     *
     * Register {@link HealthCheckCallback} with the eureka client.
     *
     * Once registered, the eureka client will invoke the
     * {@link HealthCheckCallback} in intervals specified by
     * {@link EurekaClientConfig#getInstanceInfoReplicationIntervalSeconds()}.
     *
     * @param callback app specific healthcheck.
     */
    @Deprecated
    public void registerHealthCheckCallback(HealthCheckCallback callback);

    /**
     * Register {@link HealthCheckHandler} with the eureka client.
     *
     * Once registered, the eureka client will first make an onDemand update of the
     * registering instanceInfo by calling the newly registered healthcheck handler,
     * and subsequently invoke the {@link HealthCheckHandler} in intervals specified
     * by {@link EurekaClientConfig#getInstanceInfoReplicationIntervalSeconds()}.
     *
     * @param healthCheckHandler app specific healthcheck handler.
     *                           这里注册一个健康检查器  用于判断当前节点是否可用
     */
    public void registerHealthCheck(HealthCheckHandler healthCheckHandler);

    /**
     * Register {@link EurekaEventListener} with the eureka client.
     *
     * Once registered, the eureka client will invoke {@link EurekaEventListener#onEvent} 
     * whenever there is a change in eureka client's internal state.  Use this instead of 
     * polling the client for changes.  
     * 
     * {@link EurekaEventListener#onEvent} is called from the context of an internal thread 
     * and must therefore return as quickly as possible without blocking.
     * 
     * @param eventListener
     *      为eurekaClient 注册事件监听器
     */
    public void registerEventListener(EurekaEventListener eventListener);
    
    /**
     * Unregister a {@link EurekaEventListener} previous registered with {@link EurekaClient#registerEventListener}
     * or injected into the constructor of {@link DiscoveryClient}
     * 
     * @param eventListener
     * @return True if removed otherwise false if the listener was never registered.
     *
     *      注销事件监听器
     */
    public boolean unregisterEventListener(EurekaEventListener eventListener);
    
    /**
     * @return the current registered healthcheck handler
     *      获取当前注册的心跳检测处理器
     */
    public HealthCheckHandler getHealthCheckHandler();

    // =============
    // other methods
    // =============

    /**
     * Shuts down Eureka Client. Also sends a deregistration request to the eureka server.
     * 终止当前client  同时会发送下线信息给 eurekaServer
     */
    public void shutdown();
    
    /**
     * @return the configuration of this eureka client
     *      获取eurekaClient 的配置信息
     */
    public EurekaClientConfig getEurekaClientConfig();
    
    /**
     * @return the application info manager of this eureka client
     *      获取应用管理器
     */
    public ApplicationInfoManager getApplicationInfoManager();
}
