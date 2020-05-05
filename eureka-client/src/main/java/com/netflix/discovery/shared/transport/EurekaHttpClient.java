package com.netflix.discovery.shared.transport;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;

/**
 * Low level Eureka HTTP client API.
 *
 * @author Tomasz Bak
 * 该client是用于与 eureka-server 通信的
 */
public interface EurekaHttpClient {

    /**
     * 将某个实例信息注册到 eurekaServer
     * @param info
     * @return
     */
    EurekaHttpResponse<Void> register(InstanceInfo info);

    /**
     * 发送某个实例下线的请求
     * @param appName
     * @param id
     * @return
     */
    EurekaHttpResponse<Void> cancel(String appName, String id);

    /**
     * 发送某个实例的心跳包
     * @param appName
     * @param id
     * @param info
     * @param overriddenStatus
     * @return
     */
    EurekaHttpResponse<InstanceInfo> sendHeartBeat(String appName, String id, InstanceInfo info, InstanceStatus overriddenStatus);

    /**
     * 更新当前状态  状态 对应到 UP DOWN 等
     * @param appName
     * @param id
     * @param newStatus
     * @param info
     * @return
     */
    EurekaHttpResponse<Void> statusUpdate(String appName, String id, InstanceStatus newStatus, InstanceInfo info);

    /**
     * 删除状态
     * @param appName
     * @param id
     * @param info
     * @return
     */
    EurekaHttpResponse<Void> deleteStatusOverride(String appName, String id, InstanceInfo info);

    /**
     * 根据 region 返回一组 Applications 每个Applications 内部 包含一组 application
     * @param regions
     * @return
     */
    EurekaHttpResponse<Applications> getApplications(String... regions);

    /**
     * 代表获取增量数据
     * @param regions
     * @return
     */
    EurekaHttpResponse<Applications> getDelta(String... regions);

    /**
     * 获取 vip applications
     * @param vipAddress
     * @param regions
     * @return
     */
    EurekaHttpResponse<Applications> getVip(String vipAddress, String... regions);

    /**
     * 获取 以 https 访问的 且是 vip 的 所有 applications
     * @param secureVipAddress
     * @param regions
     * @return
     */
    EurekaHttpResponse<Applications> getSecureVip(String secureVipAddress, String... regions);

    /**
     * 根据 应用名定位到为一个 application
     * @param appName
     * @return
     */
    EurekaHttpResponse<Application> getApplication(String appName);

    /**
     * 通过app 和 ID 定位到唯一的 application
     * @param appName
     * @param id
     * @return
     */
    EurekaHttpResponse<InstanceInfo> getInstance(String appName, String id);

    /**
     * 通过id 定位到 唯一application
     * @param id
     * @return
     */
    EurekaHttpResponse<InstanceInfo> getInstance(String id);

    /**
     * 发出停机请求
     */
    void shutdown();
}
