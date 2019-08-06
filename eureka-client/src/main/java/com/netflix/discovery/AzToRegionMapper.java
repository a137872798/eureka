package com.netflix.discovery;

/**
 * An interface that contains a contract of mapping availability zone to region mapping. An implementation will always
 * know before hand which zone to region mapping will be queried from the mapper, this will aid caching of this
 * information before hand.
 *
 * @author Nitesh Kant
 * 代表 可用zone 到 region 的映射
 */
public interface AzToRegionMapper {

    /**
     * Returns the region for the passed availability zone.
     *
     * @param availabilityZone Availability zone for which the region is to be retrieved.
     *
     * @return The region for the passed zone.
     * 传入一个可用的 zone 获取到对应的region
     */
    String getRegionForAvailabilityZone(String availabilityZone);

    /**
     * Update the regions that this mapper knows about.
     *
     * @param regionsToFetch Regions to fetch. This should be the super set of all regions that this mapper should know.
     *                       更新regin数组信息
     */
    void setRegionsToFetch(String[] regionsToFetch);

    /**
     * Updates the mappings it has if they depend on an external source.
     * 更新映射
     */
    void refreshMapping();
}
