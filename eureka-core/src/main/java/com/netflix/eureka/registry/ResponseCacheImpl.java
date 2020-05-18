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

package com.netflix.eureka.registry;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.netflix.appinfo.EurekaAccept;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.converters.wrappers.EncoderWrapper;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.Version;
import com.netflix.eureka.resources.CurrentRequestVersion;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import com.netflix.servo.monitor.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that is responsible for caching registry information that will be
 * queried by the clients.
 *
 * <p>
 * The cache is maintained in compressed and non-compressed form for three
 * categories of requests - all applications, delta changes and for individual
 * applications. The compressed form is probably the most efficient in terms of
 * network traffic especially when querying all applications.
 *
 * The cache also maintains separate pay load for <em>JSON</em> and <em>XML</em>
 * formats and for multiple versions too.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 * 针对响应结果做了缓存  首先考虑一点这里是在认为针对大多数请求 每次响应的数据中有很多可能重复的地方 所以选择通过该类来提高性能 减少GC
 */
public class ResponseCacheImpl implements ResponseCache {

    private static final Logger logger = LoggerFactory.getLogger(ResponseCacheImpl.class);

    public static final String ALL_APPS = "ALL_APPS";
    public static final String ALL_APPS_DELTA = "ALL_APPS_DELTA";

    // FIXME deprecated, here for backwards compatibility.
    private static final AtomicLong versionDeltaLegacy = new AtomicLong(0);
    private static final AtomicLong versionDeltaWithRegionsLegacy = new AtomicLong(0);

    private static final String EMPTY_PAYLOAD = "";
    private final java.util.Timer timer = new java.util.Timer("Eureka-CacheFillTimer", true);

    /**
     * 每当获取一次增量数据时 会增加对应的值  就是版本号的概念  避免ABA?
     */
    private final AtomicLong versionDelta = new AtomicLong(0);
    private final AtomicLong versionDeltaWithRegions = new AtomicLong(0);

    private final Timer serializeAllAppsTimer = Monitors.newTimer("serialize-all");
    private final Timer serializeDeltaAppsTimer = Monitors.newTimer("serialize-all-delta");
    private final Timer serializeAllAppsWithRemoteRegionTimer = Monitors.newTimer("serialize-all_remote_region");
    private final Timer serializeDeltaAppsWithRemoteRegionTimer = Monitors.newTimer("serialize-all-delta_remote_region");
    private final Timer serializeOneApptimer = Monitors.newTimer("serialize-one");
    private final Timer serializeViptimer = Monitors.newTimer("serialize-one-vip");
    private final Timer compressPayloadTimer = Monitors.newTimer("compress-payload");

    /**
     * This map holds mapping of keys without regions to a list of keys with region (provided by clients)
     * Since, during invalidation, triggered by a change in registry for local region, we do not know the regions
     * requested by clients, we use this mapping to get all the keys with regions to be invalidated.
     * If we do not do this, any cached user requests containing region keys will not be invalidated and will stick
     * around till expiry. Github issue: https://github.com/Netflix/eureka/issues/118
     * 该对象存储的是 Key的映射关系  （某个不包含region的key 以及与它信息相同的某个region的key  通过这些key可以从缓存中快速定位到Value）
     */
    private final Multimap<Key, Key> regionSpecificKeys =
            Multimaps.newListMultimap(new ConcurrentHashMap<Key, Collection<Key>>(), new Supplier<List<Key>>() {
                @Override
                public List<Key> get() {
                    return new CopyOnWriteArrayList<Key>();
                }
            });

    /**
     * 该容器专门负责读取数据    每次清理缓存的时候 只会清理 readWriteCacheMap 内部的数据 那么此时 readOnlyCacheMap 就可能存在脏数据
     */
    private final ConcurrentMap<Key, Value> readOnlyCacheMap = new ConcurrentHashMap<Key, Value>();

    /**
     * 谷歌的并发容器 用于存储数据
     */
    private final LoadingCache<Key, Value> readWriteCacheMap;
    /**
     * 是否使用 readOnlyCacheMap
     */
    private final boolean shouldUseReadOnlyResponseCache;
    /**
     * 该缓存所属的 registry
     */
    private final AbstractInstanceRegistry registry;
    /**
     * 服务器配置对象
     */
    private final EurekaServerConfig serverConfig;
    /**
     * 服务器 编解码器
     */
    private final ServerCodecs serverCodecs;

    /**
     * @param serverConfig
     * @param serverCodecs
     * @param registry
     */
    ResponseCacheImpl(EurekaServerConfig serverConfig, ServerCodecs serverCodecs, AbstractInstanceRegistry registry) {
        this.serverConfig = serverConfig;
        this.serverCodecs = serverCodecs;
        // 获取是否使用只读缓存对象
        this.shouldUseReadOnlyResponseCache = serverConfig.shouldUseReadOnlyResponseCache();
        this.registry = registry;

        // 获取更新缓存的时间间隔
        long responseCacheUpdateIntervalMs = serverConfig.getResponseCacheUpdateIntervalMs();
        // 构建缓存容器
        this.readWriteCacheMap =
                // 设置初始容量
                CacheBuilder.newBuilder().initialCapacity(serverConfig.getInitialCapacityOfResponseCache())
                        // 设置缓存时间
                        .expireAfterWrite(serverConfig.getResponseCacheAutoExpirationInSeconds(), TimeUnit.SECONDS)
                        // 设置缓存移除时的监听器
                        .removalListener(new RemovalListener<Key, Value>() {
                            @Override
                            public void onRemoval(RemovalNotification<Key, Value> notification) {
                                Key removedKey = notification.getKey();
                                // 缓存键中存在 地区信息
                                if (removedKey.hasRegions()) {
                                    // copy 一个不携带 region 的 缓存键
                                    Key cloneWithNoRegions = removedKey.cloneWithoutRegions();
                                    // 从相关容器中移除数据
                                    regionSpecificKeys.remove(cloneWithNoRegions, removedKey);
                                }
                            }
                        })
                        // 该方法应该是指定的key还不存在时 生成value的逻辑
                        .build(new CacheLoader<Key, Value>() {
                            /**
                             * @param key
                             * @return
                             * @throws Exception
                             */
                            @Override
                            public Value load(Key key) throws Exception {
                                // key 如果存在region 那么就在 regionSpecificKeys 额外维护一份数据
                                if (key.hasRegions()) {
                                    Key cloneWithNoRegions = key.cloneWithoutRegions();
                                    regionSpecificKeys.put(cloneWithNoRegions, key);
                                }
                                // 通过 key 生成 value
                                Value value = generatePayload(key);
                                return value;
                            }
                        });

        // 如果允许使用 readOnlyResponseCache  那么需要定期将写容器的数据转移到读容器  相当于一个快照 这样的好处就是降低单容器的读写冲突
        if (shouldUseReadOnlyResponseCache) {
            timer.schedule(getCacheUpdateTask(),
                    new Date(((System.currentTimeMillis() / responseCacheUpdateIntervalMs) * responseCacheUpdateIntervalMs)
                            + responseCacheUpdateIntervalMs),
                    responseCacheUpdateIntervalMs);
        }

        try {
            // 监控该对象的信息
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register the JMX monitor for the InstanceRegistry", e);
        }
    }

    /**
     * 该对象负责同步读写容器与只读容器的数据
     * @return
     */
    private TimerTask getCacheUpdateTask() {
        return new TimerTask() {
            @Override
            public void run() {
                logger.debug("Updating the client cache from response cache");
                // 获取 只读缓存中所有数据
                for (Key key : readOnlyCacheMap.keySet()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Updating the client cache from response cache for key : {} {} {} {}",
                                key.getEntityType(), key.getName(), key.getVersion(), key.getType());
                    }
                    try {
                        CurrentRequestVersion.set(key.getVersion());
                        // 使用 readWrite中的数据 去 同步readOnly中的数据
                        Value cacheValue = readWriteCacheMap.get(key);
                        Value currentCacheValue = readOnlyCacheMap.get(key);
                        if (cacheValue != currentCacheValue) {
                            readOnlyCacheMap.put(key, cacheValue);
                        }
                    } catch (Throwable th) {
                        logger.error("Error while updating the client cache from response cache for key {}", key.toStringCompact(), th);
                    } finally {
                        CurrentRequestVersion.remove();
                    }
                }
            }
        };
    }

    /**
     * Get the cached information about applications.
     *
     * <p>
     * If the cached information is not available it is generated on the first
     * request. After the first request, the information is then updated
     * periodically by a background thread.
     * </p>
     *
     * @param key the key for which the cached information needs to be obtained.
     * @return payload which contains information about the applications.
     */
    @Override
    public String get(final Key key) {
        return get(key, shouldUseReadOnlyResponseCache);
    }

    /**
     * 当使用 readOnlyResponseCache 时 就可以减小读写容器的锁竞争
     * @param key
     * @param useReadOnlyCache
     * @return
     */
    @VisibleForTesting
    String get(final Key key, boolean useReadOnlyCache) {
        // 从缓存中找到 对应的Value
        Value payload = getValue(key, useReadOnlyCache);
        if (payload == null || payload.getPayload().equals(EMPTY_PAYLOAD)) {
            return null;
        } else {
            // 获取 未压缩的数据
            return payload.getPayload();
        }
    }

    /**
     * Get the compressed information about the applications.
     *
     * @param key
     *            the key for which the compressed cached information needs to
     *            be obtained.
     * @return compressed payload which contains information about the
     *         applications.
     *         获取压缩数据
     */
    @Override
    public byte[] getGZIP(Key key) {
        Value payload = getValue(key, shouldUseReadOnlyResponseCache);
        if (payload == null) {
            return null;
        }
        return payload.getGzipped();
    }

    @Override
    public void stop() {
        timer.cancel();
        Monitors.unregisterObject(this);
    }

    /**
     * Invalidate the cache of a particular application.
     *
     * @param appName the application name of the application.
     *                标记缓存中某个数据变成无效数据
     */
    @Override
    public void invalidate(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress) {
        for (Key.KeyType type : Key.KeyType.values()) {
            for (Version v : Version.values()) {
                invalidate(
                        // 这样剩下的就是 其他appName 对应的缓存信息
                        new Key(Key.EntityType.Application, appName, type, v, EurekaAccept.full),   // 先清除匹配appName的缓存
                        new Key(Key.EntityType.Application, appName, type, v, EurekaAccept.compact),
                        new Key(Key.EntityType.Application, ALL_APPS, type, v, EurekaAccept.full),   // 既然某个app的数据无效了 那么自然要进行全局性清理
                        new Key(Key.EntityType.Application, ALL_APPS, type, v, EurekaAccept.compact),
                        new Key(Key.EntityType.Application, ALL_APPS_DELTA, type, v, EurekaAccept.full),
                        new Key(Key.EntityType.Application, ALL_APPS_DELTA, type, v, EurekaAccept.compact)
                );
                // 将指定地址的也标记成无效
                if (null != vipAddress) {
                    invalidate(new Key(Key.EntityType.VIP, vipAddress, type, v, EurekaAccept.full));
                }
                if (null != secureVipAddress) {
                    invalidate(new Key(Key.EntityType.SVIP, secureVipAddress, type, v, EurekaAccept.full));
                }
            }
        }
    }

    /**
     * Invalidate the cache information given the list of keys.
     *
     * @param keys the list of keys for which the cache information needs to be invalidated.
     *             使得某个 Key 对应的缓存失效
     */
    public void invalidate(Key... keys) {
        for (Key key : keys) {
            logger.debug("Invalidating the response cache key : {} {} {} {}, {}",
                    key.getEntityType(), key.getName(), key.getVersion(), key.getType(), key.getEurekaAccept());

            readWriteCacheMap.invalidate(key);
            // 如果某个key 没有限定region范围 那么要将限定范围的key 也同时设置成无效
            Collection<Key> keysWithRegions = regionSpecificKeys.get(key);
            if (null != keysWithRegions && !keysWithRegions.isEmpty()) {
                for (Key keysWithRegion : keysWithRegions) {
                    logger.debug("Invalidating the response cache key : {} {} {} {} {}",
                            key.getEntityType(), key.getName(), key.getVersion(), key.getType(), key.getEurekaAccept());
                    // 关联的也要移除
                    readWriteCacheMap.invalidate(keysWithRegion);
                }
            }
        }
    }

    /**
     * Gets the version number of the cached data.
     *
     * @return teh version number of the cached data.
     * 每当生成一次增量数据缓存时 就+1 就像版本号一样
     */
    @Override
    public AtomicLong getVersionDelta() {
        return versionDelta;
    }

    /**
     * Gets the version number of the cached data with remote regions.
     *
     * @return teh version number of the cached data with remote regions.
     */
    @Override
    public AtomicLong getVersionDeltaWithRegions() {
        return versionDeltaWithRegions;
    }

    /**
     * @deprecated use instance method {@link #getVersionDelta()}
     *
     * Gets the version number of the cached data.
     *
     * @return teh version number of the cached data.
     */
    @Deprecated
    public static AtomicLong getVersionDeltaStatic() {
        return versionDeltaLegacy;
    }

    /**
     * @deprecated use instance method {@link #getVersionDeltaWithRegions()}
     *
     * Gets the version number of the cached data with remote regions.
     *
     * @return teh version number of the cached data with remote regions.
     */
    @Deprecated
    public static AtomicLong getVersionDeltaWithRegionsLegacy() {
        return versionDeltaWithRegionsLegacy;
    }

    /**
     * Get the number of items in the response cache.
     *
     * @return int value representing the number of items in response cache.
     */
    @Monitor(name = "responseCacheSize", type = DataSourceType.GAUGE)
    public int getCurrentSize() {
        return readWriteCacheMap.asMap().size();
    }

    /**
     * Get the payload in both compressed and uncompressed form.
     */
    @VisibleForTesting
    Value getValue(final Key key, boolean useReadOnlyCache) {
        Value payload = null;
        try {
            if (useReadOnlyCache) {
                final Value currentPayload = readOnlyCacheMap.get(key);
                if (currentPayload != null) {
                    payload = currentPayload;
                } else {
                    payload = readWriteCacheMap.get(key);
                    readOnlyCacheMap.put(key, payload);
                }
            } else {
                // 直接尝试从一级缓存中获取
                payload = readWriteCacheMap.get(key);
            }
        } catch (Throwable t) {
            logger.error("Cannot get value for key : {}", key, t);
        }
        return payload;
    }

    /**
     * Generate pay load with both JSON and XML formats for all applications.
     * 此时已经通过key中的关键信息获取到对应的所有应用实例了
     */
    private String getPayLoad(Key key, Applications apps) {
        // 获取对应的编码器
        EncoderWrapper encoderWrapper = serverCodecs.getEncoder(key.getType(), key.getEurekaAccept());
        String result;
        try {
            // 根据相关信息返回匹配的编码器  并对app进行编码
            result = encoderWrapper.encode(apps);
        } catch (Exception e) {
            logger.error("Failed to encode the payload for all apps", e);
            return "";
        }
        if(logger.isDebugEnabled()) {
            logger.debug("New application cache entry {} with apps hashcode {}", key.toStringCompact(), apps.getAppsHashCode());
        }
        return result;
    }

    /**
     * Generate pay load with both JSON and XML formats for a given application.
     * 按照 key 的类型 json/xml gzip/normal 生成对应的 编解码器对象
     */
    private String getPayLoad(Key key, Application app) {
        if (app == null) {
            return EMPTY_PAYLOAD;
        }

        EncoderWrapper encoderWrapper = serverCodecs.getEncoder(key.getType(), key.getEurekaAccept());
        try {
            return encoderWrapper.encode(app);
        } catch (Exception e) {
            logger.error("Failed to encode the payload for application {}", app.getName(), e);
            return "";
        }
    }

    /*
     * Generate pay load for the given key.
     * 基于传入的key  生成数据的方法
     */
    private Value generatePayload(Key key) {
        Stopwatch tracer = null;
        try {
            String payload;
            switch (key.getEntityType()) {
                // 代表需要获取的是 普通的应用实例对象
                case Application:
                    // 获取 region信息
                    boolean isRemoteRegionRequested = key.hasRegions();

                    // 代表本次要生成的缓存数据是所有的APP
                    if (ALL_APPS.equals(key.getName())) {
                        // 判断是否有指定 region
                        if (isRemoteRegionRequested) {
                            // 启动对应的秒表
                            tracer = serializeAllAppsWithRemoteRegionTimer.start();
                            // 通过本节点获取 所属的 eureka-server 集群下所有节点记录的 APP 信息
                            payload = getPayLoad(key, registry.getApplicationsFromMultipleRegions(key.getRegions()));
                        } else {
                            tracer = serializeAllAppsTimer.start();
                            // 在没有region限制的情况下 获取所有的apps
                            payload = getPayLoad(key, registry.getApplications());
                        }
                    // 代表只获取增量数据
                    } else if (ALL_APPS_DELTA.equals(key.getName())) {
                        if (isRemoteRegionRequested) {
                            tracer = serializeDeltaAppsWithRemoteRegionTimer.start();
                            versionDeltaWithRegions.incrementAndGet();
                            versionDeltaWithRegionsLegacy.incrementAndGet();
                            payload = getPayLoad(key,
                                    registry.getApplicationDeltasFromMultipleRegions(key.getRegions()));
                        } else {
                            tracer = serializeDeltaAppsTimer.start();
                            versionDelta.incrementAndGet();
                            versionDeltaLegacy.incrementAndGet();
                            payload = getPayLoad(key, registry.getApplicationDeltas());
                        }
                    // 默认 情况下使用key.name拉取应用
                    } else {
                        tracer = serializeOneApptimer.start();
                        payload = getPayLoad(key, registry.getApplication(key.getName()));
                    }
                    break;
                // 代表需要获取的是 VIP 或 SVIP 数据
                case VIP:
                case SVIP:
                    tracer = serializeViptimer.start();
                    // 根据vip 信息筛选 出 applications 下所有 地址匹配的 app 和instance并返回
                    payload = getPayLoad(key, getApplicationsForVip(key, registry));
                    break;
                default:
                    logger.error("Unidentified entity type: {} found in the cache key.", key.getEntityType());
                    payload = "";
                    break;
            }
            return new Value(payload);
        } finally {
            if (tracer != null) {
                tracer.stop();
            }
        }
    }

    /**
     * 获取VIP实例信息
     * @param key
     * @param registry  注册中心实例
     * @return
     */
    private static Applications getApplicationsForVip(Key key, AbstractInstanceRegistry registry) {
        logger.debug(
                "Retrieving applications from registry for key : {} {} {} {}",
                key.getEntityType(), key.getName(), key.getVersion(), key.getType());
        // 初始化一个空的 app应用列表
        Applications toReturn = new Applications();
        // 从传入的注册中心获取全部的 应用
        Applications applications = registry.getApplications();
        // 返回一组副本  避免修改到源数据
        for (Application application : applications.getRegisteredApplications()) {
            Application appToAdd = null;
            // 获取所有服务实例对象
            for (InstanceInfo instanceInfo : application.getInstances()) {
                String vipAddress;
                // 获取对应的 vip 地址
                if (Key.EntityType.VIP.equals(key.getEntityType())) {
                    vipAddress = instanceInfo.getVIPAddress();
                } else if (Key.EntityType.SVIP.equals(key.getEntityType())) {
                    vipAddress = instanceInfo.getSecureVipAddress();
                } else {
                    // should not happen, but just in case.
                    continue;
                }

                if (null != vipAddress) {
                    // 尝试进行分割
                    String[] vipAddresses = vipAddress.split(",");
                    Arrays.sort(vipAddresses);
                    // 针对传入 VIP SVIP 类型 的 key keyName 就是 vipAddress 这里是找到匹配的app 组合成apps并返回
                    if (Arrays.binarySearch(vipAddresses, key.getName()) >= 0) {
                        if (null == appToAdd) {
                            // 只要application 下有一个 instance 存在 该vip 地址就设置进去
                            appToAdd = new Application(application.getName());
                            // 将结果设置到 toreturn中
                            toReturn.addApplication(appToAdd);
                        }
                        appToAdd.addInstance(instanceInfo);
                    }
                }
            }
        }
        toReturn.setAppsHashCode(toReturn.getReconcileHashCode());
        logger.debug(
                "Retrieved applications from registry for key : {} {} {} {}, reconcile hashcode: {}",
                key.getEntityType(), key.getName(), key.getVersion(), key.getType(),
                toReturn.getReconcileHashCode());
        return toReturn;
    }

    /**
     * The class that stores payload in both compressed and uncompressed form.
     * 当基于缓存键 从registry获取到相关信息后 信息会被编码成字符串 然后保存在这个对象内
     */
    public class Value {

        /**
         * 原始数据
         */
        private final String payload;
        // 压缩后的数据
        private byte[] gzipped;

        public Value(String payload) {
            this.payload = payload;
            // 代表 维护的不是 空数据
            if (!EMPTY_PAYLOAD.equals(payload)) {
                // 生成压缩数据
                Stopwatch tracer = compressPayloadTimer.start();
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    GZIPOutputStream out = new GZIPOutputStream(bos);
                    byte[] rawBytes = payload.getBytes();
                    out.write(rawBytes);
                    // Finish creation of gzip file
                    out.finish();
                    out.close();
                    bos.close();
                    gzipped = bos.toByteArray();
                } catch (IOException e) {
                    gzipped = null;
                } finally {
                    if (tracer != null) {
                        // 统计时间
                        tracer.stop();
                    }
                }
            } else {
                gzipped = null;
            }
        }

        /**
         * 获取未压缩数据
         * @return
         */
        public String getPayload() {
            return payload;
        }

        /**
         * 获取压缩数据
         * @return
         */
        public byte[] getGzipped() {
            return gzipped;
        }

    }

}
