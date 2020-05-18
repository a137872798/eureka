package com.netflix.discovery;

/**
 * An interface that contains a contract of mapping availability zone to region mapping. An implementation will always
 * know before hand which zone to region mapping will be queried from the mapper, this will aid caching of this
 * information before hand.
 *
 * @author Nitesh Kant
 * 代表 availableZone 到 region 的映射
 */
public interface AzToRegionMapper {

    /**
     * Returns the region for the passed availability zone.
     *
     * @param availabilityZone Availability zone for which the region is to be retrieved.
     *
     * @return The region for the passed zone.
     * 将可用地区转换成 region
     */
    String getRegionForAvailabilityZone(String availabilityZone);

    /**
     * Update the regions that this mapper knows about.
     *
     * @param regionsToFetch Regions to fetch. This should be the super set of all regions that this mapper should know.
     *                       代表之后会从该region下所有可用zone下拉取数据
     */
    void setRegionsToFetch(String[] regionsToFetch);

    /**
     * Updates the mappings it has if they depend on an external source.
     * 刷新当前映射关系
     */
    void refreshMapping();
}
