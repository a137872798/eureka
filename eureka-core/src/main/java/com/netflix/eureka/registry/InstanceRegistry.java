package com.netflix.eureka.registry;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.LookupService;
import com.netflix.discovery.shared.Pair;
import com.netflix.eureka.lease.LeaseManager;

import java.util.List;
import java.util.Map;

/**
 * @author Tomasz Bak
 * 对应注册中心本身  内嵌在eureka-server上
 * 本身还实现了 续约管理器 和 查找服务接口
 */
public interface InstanceRegistry extends LeaseManager<InstanceInfo>, LookupService<String> {

    /**
     * 启动注册中心 允许其他client发送请求到本节点
     * @param applicationInfoManager
     * @param count
     */
    void openForTraffic(ApplicationInfoManager applicationInfoManager, int count);

    /**
     * 终止该 实例
     */
    void shutdown();

    @Deprecated
    void storeOverriddenStatusIfRequired(String id, InstanceStatus overriddenStatus);

    /**
     * 根据传入的 status 更新该服务实例
     * @param appName
     * @param id
     * @param overriddenStatus
     */
    void storeOverriddenStatusIfRequired(String appName, String id, InstanceStatus overriddenStatus);

    /**
     * 更新实例状态 并根据 isReplication 判断本请求是否要同步到集群其他节点
     * @param appName
     * @param id
     * @param newStatus
     * @param lastDirtyTimestamp
     * @param isReplication
     * @return
     */
    boolean statusUpdate(String appName, String id, InstanceStatus newStatus,
                         String lastDirtyTimestamp, boolean isReplication);

    /**
     * 将某个变更的状态删除
     * @param appName
     * @param id
     * @param newStatus
     * @param lastDirtyTimestamp
     * @param isReplication
     * @return
     */
    boolean deleteStatusOverride(String appName, String id, InstanceStatus newStatus,
                                 String lastDirtyTimestamp, boolean isReplication);

    /**
     * 获取服务实例当前将要重置的状态快照
     * @return
     */
    Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot();

    /**
     * 仅获取本region的应用实例
     * @return
     */
    Applications getApplicationsFromLocalRegionOnly();

    /**
     * 排序后 获取所有服务实例
     * @return
     */
    List<Application> getSortedApplications();

    /**
     * Get application information.
     *
     * @param appName The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link java.net.URL} by this property
     *                            {@link com.netflix.eureka.EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     * 通过指定appName的方式 查找所有的实例 通过 includeRemoteRegion决定是否包含其他region的实例
     */
    Application getApplication(String appName, boolean includeRemoteRegion);

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @return the information about the instance.
     */
    InstanceInfo getInstanceByAppAndId(String appName, String id);

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link java.net.URL} by this property
     *                             {@link com.netflix.eureka.EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the information about the instance.
     */
    InstanceInfo getInstanceByAppAndId(String appName, String id, boolean includeRemoteRegions);

    /**
     * 清空注册中心维护的服务实例信息
     */
    void clearRegistry();

    /**
     * 初始化 缓存
     */
    void initializedResponseCache();

    /**
     * 获取缓存
     * @return
     */
    ResponseCache getResponseCache();

    /**
     * 获取 一分钟 续约 次数
     * @return
     */
    long getNumOfRenewsInLastMin();

    /**
     * 获取续约最小的阈值
     */
    int getNumOfRenewsPerMinThreshold();

    int isBelowRenewThresold();

    List<Pair<Long, String>> getLastNRegisteredInstances();

    List<Pair<Long, String>> getLastNCanceledInstances();

    /**
     * Checks whether lease expiration is enabled.
     * 是否允许租约过期
     * @return true if enabled
     */
    boolean isLeaseExpirationEnabled();

    /**
     * 是否开启自我保护模式
     * @return
     */
    boolean isSelfPreservationModeEnabled();

}
