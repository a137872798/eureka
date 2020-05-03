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
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.InstanceRegionChecker;
import com.netflix.discovery.provider.Serializer;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamImplicit;

/**
 * The class that wraps all the registry information returned by eureka server.
 *
 * <p>
 * Note that the registry information is fetched from eureka server as specified
 * in {@link EurekaClientConfig#getRegistryFetchIntervalSeconds()}. Once the
 * information is fetched it is shuffled and also filtered for instances with
 * {@link InstanceStatus#UP} status as specified by the configuration
 * {@link EurekaClientConfig#shouldFilterOnlyUpInstances()}.
 * </p>
 *
 * @author Karthik Ranganathan
 *
 *      Application 的 集合
 */
@Serializer("com.netflix.discovery.converters.EntityBodyConverter")
@XStreamAlias("applications")
@JsonRootName("applications")
public class Applications {


    private static class VipIndexSupport {
        /**
         * 同步链表
         */
        final AbstractQueue<InstanceInfo> instances = new ConcurrentLinkedQueue<>();
        /**
         * 轮询的 下标 起始值为0
         */
        final AtomicLong roundRobinIndex = new AtomicLong(0);
        /**
         * 内部维护了一组 服务实例
         */
        final AtomicReference<List<InstanceInfo>> vipList = new AtomicReference<List<InstanceInfo>>(Collections.emptyList());

        public AtomicLong getRoundRobinIndex() {
            return roundRobinIndex;
        }

        public AtomicReference<List<InstanceInfo>> getVipList() {
            return vipList;
        }
    }

    private static final String STATUS_DELIMITER = "_";

    /**
     * 这组应用对应的 hashCode
     */
    private String appsHashCode;

    private Long versionDelta;
    @XStreamImplicit
    private final AbstractQueue<Application> applications;
    /**
     * 该组 apps 中 appName 与 app 的键值对
     */
    private final Map<String, Application> appNameApplicationMap;
    private final Map<String, VipIndexSupport> virtualHostNameAppMap;
    private final Map<String, VipIndexSupport> secureVirtualHostNameAppMap;

    /**
     * Create a new, empty Eureka application list.
     * 初始化 DiscoveryClient 时  会以该种方式初始化对象
     */
    public Applications() {
        this(null, -1L, Collections.emptyList());
    }

    /**
     * Note that appsHashCode and versionDelta key names are formatted in a
     * custom/configurable way.
     * 使用 json 字符串 初始化
     */
    @JsonCreator
    public Applications(@JsonProperty("appsHashCode") String appsHashCode,
            @JsonProperty("versionDelta") Long versionDelta,
            @JsonProperty("application") List<Application> registeredApplications) {
        //这里为什么要使用并发队列来维护 当前能获取的所有应用  应该是有多个时机 会修改该列表 该队列是通过 自旋+CAS 也就是乐观锁 实现 添加元素的  适合于
        //竞争不激烈 并且 对可见性要求高的场景 copyOnWrite数组 允许读取到过期数据那么 如果某一时刻 读取到 过期数据 且进行缓存 那么在下次更新缓存列表前 都会访问该失效服务 所以
        //不能使用基于 copyOnWrite数组数组的 实现
        this.applications = new ConcurrentLinkedQueue<Application>();
        this.appsHashCode = appsHashCode;
        this.appNameApplicationMap = new ConcurrentHashMap<String, Application>();
        this.virtualHostNameAppMap = new ConcurrentHashMap<String, VipIndexSupport>();
        this.secureVirtualHostNameAppMap = new ConcurrentHashMap<String, VipIndexSupport>();
        this.versionDelta = versionDelta;

        for (Application app : registeredApplications) {
            // 这里会同时给 3个map 填充数据
            this.addApplication(app);
        }
    }

    /**
     * Add the <em>application</em> to the list.
     *
     * @param app
     *            the <em>application</em> to be added.
     *            将本应用实例保存到list 中
     */
    public void addApplication(Application app) {
        appNameApplicationMap.put(app.getName().toUpperCase(Locale.ROOT), app);
        // 将app 根据情况设置到2个map中的一个
        addInstancesToVIPMaps(app, this.virtualHostNameAppMap, this.secureVirtualHostNameAppMap);
        applications.add(app);
    }

    /**
     * Gets the list of all registered <em>applications</em> from eureka.
     *
     * @return list containing all applications registered with eureka.
     */
    @JsonProperty("application")
    public List<Application> getRegisteredApplications() {
        return new ArrayList<Application>(this.applications);
    }

    /**
     * Gets the registered <em>application</em> for the given
     * application name.
     *
     * @param appName
     *            the application name for which the result need to be fetched.
     * @return the registered application for the given application
     *         name.
     */
    public Application getRegisteredApplications(String appName) {
        return appNameApplicationMap.get(appName.toUpperCase(Locale.ROOT));
    }

    /**
     * Gets the list of <em>instances</em> associated to a virtual host name.
     *
     * @param virtualHostName
     *            the virtual hostname for which the instances need to be
     *            returned.
     * @return list of <em>instances</em>.
     *      通过虚拟主机 获取 服务实例信息
     */
    public List<InstanceInfo> getInstancesByVirtualHostName(String virtualHostName) {
        return Optional.ofNullable(this.virtualHostNameAppMap.get(virtualHostName.toUpperCase(Locale.ROOT)))
                // VipIndexSupport中会维护一个VipList
            .map(VipIndexSupport::getVipList)
            .map(AtomicReference::get)
            .orElseGet(Collections::emptyList); 
    }

    /**
     * Gets the list of secure <em>instances</em> associated to a virtual host
     * name.
     *
     * @param secureVirtualHostName
     *            the virtual hostname for which the secure instances need to be
     *            returned.
     * @return list of <em>instances</em>.
     * 同上
     */
    public List<InstanceInfo> getInstancesBySecureVirtualHostName(String secureVirtualHostName) {
        return Optional.ofNullable(this.secureVirtualHostNameAppMap.get(secureVirtualHostName.toUpperCase(Locale.ROOT)))
                .map(VipIndexSupport::getVipList)
                .map(AtomicReference::get)
                .orElseGet(Collections::emptyList);        
    }

    /**
     * @return a weakly consistent size of the number of instances in all the
     *         applications
     */
    public int size() {
        return applications.stream().mapToInt(Application::size).sum();
    }

    @Deprecated
    public void setVersion(Long version) {
        this.versionDelta = version;
    }

    @Deprecated
    @JsonIgnore // Handled directly due to legacy name formatting
    public Long getVersion() {
        return this.versionDelta;
    }

    /**
     * Used by the eureka server. Not for external use.
     *
     * @param hashCode
     *            the hash code to assign for this app collection
     */
    public void setAppsHashCode(String hashCode) {
        this.appsHashCode = hashCode;
    }

    /**
     * Used by the eureka server. Not for external use.
     * 
     * @return the string indicating the hashcode based on the applications
     *         stored.
     *
     */
    @JsonIgnore // Handled directly due to legacy name formatting
    public String getAppsHashCode() {
        return this.appsHashCode;
    }

    /**
     * Gets the hash code for this <em>applications</em> instance. Used for
     * comparison of instances between eureka server and eureka client.
     *
     * @return the internal hash code representation indicating the information
     *         about the instances.
     *         重新生成一个hashCode
     */
    @JsonIgnore
    public String getReconcileHashCode() {
        TreeMap<String, AtomicInteger> instanceCountMap = new TreeMap<String, AtomicInteger>();
        // 将数据填充到容器中
        populateInstanceCountMap(instanceCountMap);
        return getReconcileHashCode(instanceCountMap);
    }

    /**
     * Populates the provided instance count map. The instance count map is used
     * as part of the general app list synchronization mechanism.
     * 
     * @param instanceCountMap
     *            the map to populate
     *            将结果填充到指定容器 得到的是一个 以状态为 key 数量为 value 的容器
     */
    public void populateInstanceCountMap(Map<String, AtomicInteger> instanceCountMap) {
        for (Application app : this.getRegisteredApplications()) {
            // 获取每个 app下所有的 instanceInfo  一个app 是对应一个 注册到eurekaServer 上的 eurekaClient 吗???
            for (InstanceInfo info : app.getInstancesAsIsFromEureka()) {
                AtomicInteger instanceCount = instanceCountMap.computeIfAbsent(info.getStatus().name(),
                        k -> new AtomicInteger(0));
                instanceCount.incrementAndGet();
            }
        }
    }

    /**
     * Gets the reconciliation hashcode. The hashcode is used to determine
     * whether the applications list has changed since the last time it was
     * acquired.
     * 
     * @param instanceCountMap
     *            the instance count map to use for generating the hash
     * @return the hash code for this instance
     */
    public static String getReconcileHashCode(Map<String, AtomicInteger> instanceCountMap) {
        StringBuilder reconcileHashCode = new StringBuilder(75);
        for (Map.Entry<String, AtomicInteger> mapEntry : instanceCountMap.entrySet()) {
            reconcileHashCode.append(mapEntry.getKey()).append(STATUS_DELIMITER).append(mapEntry.getValue().get())
                    .append(STATUS_DELIMITER);
        }
        return reconcileHashCode.toString();
    }

    /**
     * Shuffles the provided instances so that they will not always be returned
     * in the same order.
     * 
     * @param filterUpInstances
     *            whether to return only UP instances
     */
    public void shuffleInstances(boolean filterUpInstances) {
        shuffleInstances(filterUpInstances, false, null, null, null);
    }

    /**
     * Shuffles a whole region so that the instances will not always be returned
     * in the same order.
     * 
     * @param remoteRegionsRegistry
     *            the map of remote region names to their registries
     * @param clientConfig
     *            the {@link EurekaClientConfig}, whose settings will be used to
     *            determine whether to filter to only UP instances
     * @param instanceRegionChecker
     *            the instance region checker
     *
     *            将本容器中的instanceInfo 填充到 map 中
     */
    public void shuffleAndIndexInstances(Map<String, Applications> remoteRegionsRegistry,
            EurekaClientConfig clientConfig, InstanceRegionChecker instanceRegionChecker) {
        // 是否只需要 状态为 UP 的 client 对象
        shuffleInstances(clientConfig.shouldFilterOnlyUpInstances(), true, remoteRegionsRegistry, clientConfig,
                instanceRegionChecker);
    }

    /**
     * 将实例数据打乱
     * @param filterUpInstances  是否只需要 UP 实例
     * @param indexByRemoteRegions  按照 region 排序???
     * @param remoteRegionsRegistry  将结果填充到该容器
     * @param clientConfig
     * @param instanceRegionChecker
     */
    private void shuffleInstances(boolean filterUpInstances, 
            boolean indexByRemoteRegions,
            @Nullable Map<String, Applications> remoteRegionsRegistry, 
            @Nullable EurekaClientConfig clientConfig,
            @Nullable InstanceRegionChecker instanceRegionChecker) {
        Map<String, VipIndexSupport> secureVirtualHostNameAppMap = new HashMap<>();
        Map<String, VipIndexSupport> virtualHostNameAppMap = new HashMap<>();
        // 遍历所有的  app
        for (Application application : appNameApplicationMap.values()) {
            // 将应用中的 实例信息 打乱
            if (indexByRemoteRegions) {
                // 将本 applications 中的实例信息移动到了 remoteRegionsRegistry 中
                application.shuffleAndStoreInstances(remoteRegionsRegistry, clientConfig, instanceRegionChecker);
            } else {
                // 这里只是移除了 非 UP状态的 apps
                application.shuffleAndStoreInstances(filterUpInstances);
            }
            // 是否属于 vip 添加到对应的容器
            this.addInstancesToVIPMaps(application, virtualHostNameAppMap, secureVirtualHostNameAppMap);
        }
        // 对2个 virtualHostName  也打乱
        shuffleAndFilterInstances(virtualHostNameAppMap, filterUpInstances);
        shuffleAndFilterInstances(secureVirtualHostNameAppMap, filterUpInstances);

        // 把上面 分类出来的数据保存到对应容器中
        this.virtualHostNameAppMap.putAll(virtualHostNameAppMap);
        this.virtualHostNameAppMap.keySet().retainAll(virtualHostNameAppMap.keySet());
        this.secureVirtualHostNameAppMap.putAll(secureVirtualHostNameAppMap);
        this.secureVirtualHostNameAppMap.keySet().retainAll(secureVirtualHostNameAppMap.keySet());
    }

    /**
     * Gets the next round-robin index for the given virtual host name. This
     * index is reset after every registry fetch cycle.
     *
     * @param virtualHostname
     *            the virtual host name.
     * @param secure
     *            indicates whether it is a secure request or a non-secure
     *            request.
     * @return AtomicLong value representing the next round-robin index.
     * 获取 host 对应的 index
     */
    public AtomicLong getNextIndex(String virtualHostname, boolean secure) {
        Map<String, VipIndexSupport> index = (secure) ? secureVirtualHostNameAppMap : virtualHostNameAppMap;
        return Optional.ofNullable(index.get(virtualHostname.toUpperCase(Locale.ROOT)))
                .map(VipIndexSupport::getRoundRobinIndex)
                .orElse(null);
    }

    /**
     * Shuffle the instances and filter for only {@link InstanceStatus#UP} if
     * required.
     * 打乱 实例信息
     */
    private void shuffleAndFilterInstances(Map<String, VipIndexSupport> srcMap, boolean filterUpInstances) {

        Random shuffleRandom = new Random();
        for (Map.Entry<String, VipIndexSupport> entries : srcMap.entrySet()) {
            VipIndexSupport vipIndexSupport = entries.getValue();
            // 获取 所有的 Vip 实例信息
            AbstractQueue<InstanceInfo> vipInstances = vipIndexSupport.instances;
            // 代表被拦截后的结果
            final List<InstanceInfo> filteredInstances;
            if (filterUpInstances) {
                // 必须 status == UP 才能被保留
                filteredInstances = vipInstances.stream().filter(ii -> ii.getStatus() == InstanceStatus.UP)
                        .collect(Collectors.toCollection(() -> new ArrayList<>(vipInstances.size())));
            } else {
                filteredInstances = new ArrayList<InstanceInfo>(vipInstances);
            }
            Collections.shuffle(filteredInstances, shuffleRandom);
            // 打乱后保存
            vipIndexSupport.vipList.set(filteredInstances);
            // 重置轮询的 下标
            vipIndexSupport.roundRobinIndex.set(0);
        }
    }

    /**
     * Add the instance to the given map based if the vip address matches with
     * that of the instance. Note that an instance can be mapped to multiple vip
     * addresses.
     *
     */
    private void addInstanceToMap(InstanceInfo info, String vipAddresses, Map<String, VipIndexSupport> vipMap) {
        if (vipAddresses != null) {
            String[] vipAddressArray = vipAddresses.toUpperCase(Locale.ROOT).split(",");
            for (String vipAddress : vipAddressArray) {
                VipIndexSupport vis = vipMap.computeIfAbsent(vipAddress, k -> new VipIndexSupport());
                vis.instances.add(info);
            }
        }
    }

    /**
     * Adds the instances to the internal vip address map.
     * 
     * @param app
     *            - the applications for which the instances need to be added.
     *            如果 app 是 vip就设置到对应的容器
     */
    private void addInstancesToVIPMaps(Application app, Map<String, VipIndexSupport> virtualHostNameAppMap,
            Map<String, VipIndexSupport> secureVirtualHostNameAppMap) {
        // Check and add the instances to the their respective virtual host name
        // mappings
        for (InstanceInfo info : app.getInstances()) {
            // 存在 vipAddress 就保存到 vip 的容器
            String vipAddresses = info.getVIPAddress();
            if (vipAddresses != null) {
                addInstanceToMap(info, vipAddresses, virtualHostNameAppMap);
            }

            // 存在 secureVipAddress 就保存到 secureVip 的容器
            String secureVipAddresses = info.getSecureVipAddress();
            if (secureVipAddresses != null) {
                addInstanceToMap(info, secureVipAddresses, secureVirtualHostNameAppMap);
            }
        }
    }

    /**
     * Remove the <em>application</em> from the list.
     *
     * @param app the <em>application</em>
     */
    public void removeApplication(Application app) {
        this.appNameApplicationMap.remove(app.getName().toUpperCase(Locale.ROOT));
        this.applications.remove(app);
    }
}
