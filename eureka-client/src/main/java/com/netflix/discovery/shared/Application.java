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

package com.netflix.discovery.shared;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.InstanceRegionChecker;
import com.netflix.discovery.provider.Serializer;
import com.netflix.discovery.util.StringCache;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

/**
 * The application class holds the list of instances for a particular
 * application.
 * 代表一个 应用的信息 每个应用下可以包含多个服务实例
 *
 * @author Karthik Ranganathan
 *
 */
@Serializer("com.netflix.discovery.converters.EntityBodyConverter")
@XStreamAlias("application")
@JsonRootName("application")
public class Application {
    
    private static Random shuffleRandom = new Random();

    @Override
    public String toString() {
        return "Application [name=" + name + ", isDirty=" + isDirty
                + ", instances=" + instances + ", shuffledInstances="
                + shuffledInstances + ", instancesMap=" + instancesMap + "]";
    }

    /**
     * 应用名
     */
    private String name;

    /**
     * 数据是否发生过改变
     */
    @XStreamOmitField
    private volatile boolean isDirty = false;

    /**
     * 一个 app中可以携带多个 instanceInfo对象
     */
    @XStreamImplicit
    private final Set<InstanceInfo> instances;

    /**
     * 被随机排序后的 instance
     */
    private final AtomicReference<List<InstanceInfo>> shuffledInstances;

    /**
     * 实例 map
     */
    private final Map<String, InstanceInfo> instancesMap;

    public Application() {
        instances = new LinkedHashSet<InstanceInfo>();
        instancesMap = new ConcurrentHashMap<String, InstanceInfo>();
        shuffledInstances = new AtomicReference<List<InstanceInfo>>();
    }

    public Application(String name) {
        this();
        // StringCache 内部使用一个WeakHashMap
        this.name = StringCache.intern(name);
    }

    @JsonCreator
    public Application(
            @JsonProperty("name") String name,
            @JsonProperty("instance") List<InstanceInfo> instances) {
        this(name);
        for (InstanceInfo instanceInfo : instances) {
            addInstance(instanceInfo);
        }
    }

    /**
     * Add the given instance info the list.
     *
     * @param i
     *            the instance info object to be added.
     *            将app 下的每个 实例 添加进来
     */
    public void addInstance(InstanceInfo i) {
        // 维护了 instanceId 和 instance的映射
        instancesMap.put(i.getId(), i);
        synchronized (instances) {
            instances.remove(i);
            instances.add(i);
            // 标记发生过改变
            isDirty = true;
        }
    }

    /**
     * Remove the given instance info the list.
     *
     * @param i
     *            the instance info object to be removed.
     *            从一个app(应用) 中移除某个实例
     */
    public void removeInstance(InstanceInfo i) {
        removeInstance(i, true);
    }

    /**
     * Gets the list of instances associated with this particular application.
     * <p>
     * Note that the instances are always returned with random order after
     * shuffling to avoid traffic to the same instances during startup. The
     * shuffling always happens once after every fetch cycle as specified in
     * {@link EurekaClientConfig#getRegistryFetchIntervalSeconds}.
     * </p>
     *
     * @return the list of shuffled instances associated with this application.
     *      获取instances 列表
     */
    @JsonProperty("instance")
    public List<InstanceInfo> getInstances() {
        // 如果没有获取到 就调用getInstancesAsIsFromEureka  该方法实现就是直接返回了 instances
        return Optional.ofNullable(shuffledInstances.get()).orElseGet(this::getInstancesAsIsFromEureka);
    }

    /**
     * Gets the list of non-shuffled and non-filtered instances associated with this particular
     * application.
     *
     * @return list of non-shuffled and non-filtered instances associated with this particular
     *         application.
     */
    @JsonIgnore
    public List<InstanceInfo> getInstancesAsIsFromEureka() {
        synchronized (instances) {
           return new ArrayList<InstanceInfo>(this.instances);
        }
    }


    /**
     * Get the instance info that matches the given id.
     *
     * @param id
     *            the id for which the instance info needs to be returned.
     * @return the instance info object.
     */
    public InstanceInfo getByInstanceId(String id) {
        return instancesMap.get(id);
    }

    /**
     * Gets the name of the application.
     *
     * @return the name of the application.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of the application.
     *
     * @param name
     *            the name of the application.
     */
    public void setName(String name) {
        this.name = StringCache.intern(name);
    }

    /**
     * @return the number of instances in this application
     */
    public int size() {
        return instances.size();
    }

    /**
     * Shuffles the list of instances in the application and stores it for
     * future retrievals.
     *
     * @param filterUpInstances
     *            indicates whether only the instances with status
     *            {@link InstanceStatus#UP} needs to be stored.
     *            打乱顺序 并保存
     */
    public void shuffleAndStoreInstances(boolean filterUpInstances) {
        _shuffleAndStoreInstances(filterUpInstances, false, null, null, null);
    }

    public void shuffleAndStoreInstances(Map<String, Applications> remoteRegionsRegistry,
                                         EurekaClientConfig clientConfig, InstanceRegionChecker instanceRegionChecker) {
        _shuffleAndStoreInstances(clientConfig.shouldFilterOnlyUpInstances(), true, remoteRegionsRegistry, clientConfig,
                instanceRegionChecker);
    }

    /**
     *
     * @param filterUpInstances 代表是否只有 status = UP 的需要保存
     * @param indexByRemoteRegions 按照 region 来进行 排序
     * @param remoteRegionsRegistry 远端 region的 注册信息   string 应该是 region 然后对应 下面的 Applications 又对应到下面多个 Application
     * @param clientConfig 客户端配置
     * @param instanceRegionChecker
     */
    private void _shuffleAndStoreInstances(boolean filterUpInstances, boolean indexByRemoteRegions,
                                           @Nullable Map<String, Applications> remoteRegionsRegistry,
                                           @Nullable EurekaClientConfig clientConfig,
                                           @Nullable InstanceRegionChecker instanceRegionChecker) {
        List<InstanceInfo> instanceInfoList;
        synchronized (instances) {
            // 这里生成一个 副本对象就是不希望 修改该容器时 影响到源数据
            instanceInfoList = new ArrayList<InstanceInfo>(instances);
        }
        // 如果设置了 根据 region 设置index  因为clientConfig 中维护了 zone 和 region的关系    regionChecker 需要借助它
        boolean remoteIndexingActive = indexByRemoteRegions && null != instanceRegionChecker && null != clientConfig
                && null != remoteRegionsRegistry;
        // 后面的标识 代表 只需要 status为 up的服务实例
        if (remoteIndexingActive || filterUpInstances) {
            Iterator<InstanceInfo> it = instanceInfoList.iterator();
            while (it.hasNext()) {
                InstanceInfo instanceInfo = it.next();
                // status 不是 up 的就要移除
                if (filterUpInstances && InstanceStatus.UP != instanceInfo.getStatus()) {
                    it.remove();
                    // 这个是代表是否需要将本地实例 转移到给定的 remoteRegionsRegistry 容器中
                } else if (remoteIndexingActive) {
                    //instanceInfo 中不存在 dataCenter 就返回 localRegion 也就是本机 region  一般是不存在数据中心的
                    String instanceRegion = instanceRegionChecker.getInstanceRegion(instanceInfo);
                    // 如果获取到的不是本地 region
                    if (!instanceRegionChecker.isLocalRegion(instanceRegion)) {
                        // 代表是 远端的region 那么获取远端region 的全部 applicaiton
                        Applications appsForRemoteRegion = remoteRegionsRegistry.get(instanceRegion);
                        if (null == appsForRemoteRegion) {
                            appsForRemoteRegion = new Applications();
                            // 不存在就设置一个空对象
                            remoteRegionsRegistry.put(instanceRegion, appsForRemoteRegion);
                        }

                        // 获取对应的 单个 应用
                        Application remoteApp =
                                appsForRemoteRegion.getRegisteredApplications(instanceInfo.getAppName());
                        if (null == remoteApp) {
                            // 添加默认实例
                            remoteApp = new Application(instanceInfo.getAppName());
                            appsForRemoteRegion.addApplication(remoteApp);
                        }

                        // 这里代表的是 从 instances 中转换到了remoteRegionsRegistry 中
                        remoteApp.addInstance(instanceInfo);
                        // 这种情况下并不是 数据无效 所以 markAsDirty 为 false
                        this.removeInstance(instanceInfo, false);
                        it.remove();
                    }
                }
            }

        }
        // 将数据打乱后 设置到 容器中
        Collections.shuffle(instanceInfoList, shuffleRandom);
        this.shuffledInstances.set(instanceInfoList);
    }

    private void removeInstance(InstanceInfo i, boolean markAsDirty) {
        instancesMap.remove(i.getId());
        synchronized (instances) {
            instances.remove(i);
            // 如果需要设置成 被改变过 就修改isDirty
            if (markAsDirty) {
                isDirty = true;
            }
        }
    }
}
