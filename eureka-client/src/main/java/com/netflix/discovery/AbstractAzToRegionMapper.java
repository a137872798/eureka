package com.netflix.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.base.Supplier;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.discovery.DefaultEurekaClientConfig.DEFAULT_ZONE;

/**
 * avaliableZone 到 region 的映射骨架类
 * @author Nitesh Kant
 */
public abstract class AbstractAzToRegionMapper implements AzToRegionMapper {

    private static final Logger logger = LoggerFactory.getLogger(InstanceRegionChecker.class);
    private static final String[] EMPTY_STR_ARRAY = new String[0];

    /**
     * eureka客户端的配置
     */
    protected final EurekaClientConfig clientConfig;

    /**
     * A default for the mapping that we know of, if a remote region is configured to be fetched but does not have
     * any availability zone mapping, we will use these defaults. OTOH, if the remote region has any mapping defaults
     * will not be used.
     * 代表 一个zone 可以对应多个region  这个代表默认的 映射关系
     * Multimap<String,String> 等价于 HashMap<String,Collection<String>>
     *     看来region的范围要大一些  内部包含多个 availableZone
     */
    private final Multimap<String, String> defaultRegionVsAzMap =
            Multimaps.newListMultimap(new HashMap<String, Collection<String>>(), new Supplier<List<String>>() {
                @Override
                public List<String> get() {
                    return new ArrayList<String>();
                }
            });

    /**
     * 一个region可以对应多个zone 反过来每个zone 都只能对应一个region 所以这里使用的是<String,String>
     */
    private final Map<String, String> availabilityZoneVsRegion = new ConcurrentHashMap<String, String>();
    /**
     * 代表哪些region 需要被更新
     */
    private String[] regionsToFetch;

    protected AbstractAzToRegionMapper(EurekaClientConfig clientConfig) {
        this.clientConfig = clientConfig;
        // 设置默认值
        populateDefaultAZToRegionMap();
    }

    /**
     * @param regionsToFetch Regions to fetch. This should be the super set of all regions that this mapper should know.
     *                       这里设置了待拉取的某个region   (会拉取到下面的availableZone吧)
     *                       这个region是从哪来的
     */
    @Override
    public synchronized void setRegionsToFetch(String[] regionsToFetch) {
        if (null != regionsToFetch) {
            this.regionsToFetch = regionsToFetch;
            logger.info("Fetching availability zone to region mapping for regions {}", (Object) regionsToFetch);
            // 清除之前维护的 可用zone 到 region 的映射关系
            availabilityZoneVsRegion.clear();
            for (String remoteRegion : regionsToFetch) {
                // 根据 region 获取 可用zone
                Set<String> availabilityZones = getZonesForARegion(remoteRegion);
                // 如果不存在可用zone 或者 zone 为 defaultZone
                if (null == availabilityZones
                        || (availabilityZones.size() == 1 && availabilityZones.contains(DEFAULT_ZONE))
                        || availabilityZones.isEmpty()) {
                    // 代表没有找到 aZone
                    logger.info("No availability zone information available for remote region: {}"
                            + ". Now checking in the default mapping.", remoteRegion);
                    // 尝试从默认的 region 和 aZone 映射中找到 zone
                    if (defaultRegionVsAzMap.containsKey(remoteRegion)) {
                        Collection<String> defaultAvailabilityZones = defaultRegionVsAzMap.get(remoteRegion);
                        for (String defaultAvailabilityZone : defaultAvailabilityZones) {
                            // 这样 多个zone 都会匹配到同一个region 上
                            availabilityZoneVsRegion.put(defaultAvailabilityZone, remoteRegion);
                        }
                        // 看来defaultZone 是不被承认的
                    } else {
                        String msg = "No availability zone information available for remote region: " + remoteRegion
                                + ". This is required if registry information for this region is configured to be "
                                + "fetched.";
                        logger.error(msg);
                        throw new RuntimeException(msg);
                    }
                } else {
                    // 保存映射关系
                    for (String availabilityZone : availabilityZones) {
                        availabilityZoneVsRegion.put(availabilityZone, remoteRegion);
                    }
                }
            }

            logger.info("Availability zone to region mapping for all remote regions: {}", availabilityZoneVsRegion);
        } else {
            logger.info("Regions to fetch is null. Erasing older mapping if any.");
            availabilityZoneVsRegion.clear();
            this.regionsToFetch = EMPTY_STR_ARRAY;
        }
    }

    /**
     * Returns all the zones in the provided region.
     * @param region the region whose zones you want
     * @return a set of zones
     * 如何根据 region 获取zones 需要子类实现
     */
    protected abstract Set<String> getZonesForARegion(String region);

    /**
     * 通过zone 寻找region
     * @param availabilityZone Availability zone for which the region is to be retrieved.
     *
     * @return
     */
    @Override
    public String getRegionForAvailabilityZone(String availabilityZone) {
        String region = availabilityZoneVsRegion.get(availabilityZone);
        if (null == region) {
            // 如果zone 没有找到对应的 region
            return parseAzToGetRegion(availabilityZone);
        }
        return region;
    }

    /**
     * 强制重新使用regions 去拉取 zone
     * 一般是在某个时机 重新设置了 regionsToFetch 后 调用该方法 拉取最新的zone
     */
    @Override
    public synchronized void refreshMapping() {
        logger.info("Refreshing availability zone to region mappings.");
        setRegionsToFetch(regionsToFetch);
    }

    /**
     * Tries to determine what region we're in, based on the provided availability zone.
     * @param availabilityZone the availability zone to inspect
     * @return the region, if available; null otherwise
     * 尝试使用zone 解析 region   这里怀疑zone信息可能携带了什么无效的标识 移除后重新检测
     */
    protected String parseAzToGetRegion(String availabilityZone) {
        // Here we see that whether the availability zone is following a pattern like <region><single letter>
        // If it is then we take ignore the last letter and check if the remaining part is actually a known remote
        // region. If yes, then we return that region, else null which means local region.
        if (!availabilityZone.isEmpty()) {
            String possibleRegion = availabilityZone.substring(0, availabilityZone.length() - 1);
            if (availabilityZoneVsRegion.containsValue(possibleRegion)) {
                return possibleRegion;
            }
        }
        return null;
    }

    /**
     * 设置了 默认的 可用zone 到 region 的映射
     */
    private void populateDefaultAZToRegionMap() {
        defaultRegionVsAzMap.put("us-east-1", "us-east-1a");
        defaultRegionVsAzMap.put("us-east-1", "us-east-1c");
        defaultRegionVsAzMap.put("us-east-1", "us-east-1d");
        defaultRegionVsAzMap.put("us-east-1", "us-east-1e");

        defaultRegionVsAzMap.put("us-west-1", "us-west-1a");
        defaultRegionVsAzMap.put("us-west-1", "us-west-1c");

        defaultRegionVsAzMap.put("us-west-2", "us-west-2a");
        defaultRegionVsAzMap.put("us-west-2", "us-west-2b");
        defaultRegionVsAzMap.put("us-west-2", "us-west-2c");

        defaultRegionVsAzMap.put("eu-west-1", "eu-west-1a");
        defaultRegionVsAzMap.put("eu-west-1", "eu-west-1b");
        defaultRegionVsAzMap.put("eu-west-1", "eu-west-1c");
    }
}
