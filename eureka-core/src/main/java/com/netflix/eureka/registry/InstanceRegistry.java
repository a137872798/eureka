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
 * 代表注册中心上的一个 实例对象 也就是 具备 注册 续约 剔除 注销 能力
 */
public interface InstanceRegistry extends LeaseManager<InstanceInfo>, LookupService<String> {

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

    boolean statusUpdate(String appName, String id, InstanceStatus newStatus,
                         String lastDirtyTimestamp, boolean isReplication);

    boolean deleteStatusOverride(String appName, String id, InstanceStatus newStatus,
                                 String lastDirtyTimestamp, boolean isReplication);

    /**
     * 获取服务实例状态 更新快照
     * @return
     */
    Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot();

    /**
     * 只获取本地的服务实例
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
     * 根据 是否携带 其他区域的 服务 通过appName 获取服务实例
     *
     * @param appName The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link java.net.URL} by this property
     *                            {@link com.netflix.eureka.EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     */
    Application getApplication(String appName, boolean includeRemoteRegion);

    /**
     * Gets the {@link InstanceInfo} information.
     * 通过id 获取服务实例信息
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @return the information about the instance.
     */
    InstanceInfo getInstanceByAppAndId(String appName, String id);

    /**
     * Gets the {@link InstanceInfo} information.
     * 通过服务实例id  以及是否包含远程地区的 服务 获取服务实例信息
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

    boolean isSelfPreservationModeEnabled();

}
