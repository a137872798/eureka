package com.netflix.eureka.registry;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author David Liu
 * 代表 针对http 请求要返回的 响应缓存
 */
public interface ResponseCache {

    /**
     * 指定某个 服务缓存无效
     * @param appName
     * @param vipAddress
     * @param secureVipAddress
     */
    void invalidate(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress);

    /**
     * 获取版本信息
     * @return
     */
    AtomicLong getVersionDelta();

    /**
     * 按照地区来获取 版本信息
     * @return
     */
    AtomicLong getVersionDeltaWithRegions();

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
     * 从缓存中获取数据
     */
     String get(Key key);

    /**
     * Get the compressed information about the applications.
     *
     * @param key the key for which the compressed cached information needs to be obtained.
     * @return compressed payload which contains information about the applications.
     * 获取被保存的 压缩数据
     */
    byte[] getGZIP(Key key);
}
