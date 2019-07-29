package com.netflix.discovery;

import javax.annotation.Nullable;
import java.util.Map;

import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.InstanceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务实例校验对象
 * @author Nitesh Kant
 */
public class InstanceRegionChecker {
    private static Logger logger = LoggerFactory.getLogger(InstanceRegionChecker.class);

    /**
     * 该对象内部维护了 zone 和 region 的映射关系
     */
    private final AzToRegionMapper azToRegionMapper;
    /**
     * 本地 region
     */
    private final String localRegion;

    InstanceRegionChecker(AzToRegionMapper azToRegionMapper, String localRegion) {
        this.azToRegionMapper = azToRegionMapper;
        this.localRegion = localRegion;
    }

    /**
     * 根据 instanceInfo 获取对应的region  看来是尝试从数据中心获取 默认是返回本地region
     * @param instanceInfo
     * @return
     */
    @Nullable
    public String getInstanceRegion(InstanceInfo instanceInfo) {
        if (instanceInfo.getDataCenterInfo() == null || instanceInfo.getDataCenterInfo().getName() == null) {
            logger.warn("Cannot get region for instance id:{}, app:{} as dataCenterInfo is null. Returning local:{} by default",
                    instanceInfo.getId(), instanceInfo.getAppName(), localRegion);

            return localRegion;
        }
        // 如果该instance 对应的 dataCenterName 是 amazon
        if (DataCenterInfo.Name.Amazon.equals(instanceInfo.getDataCenterInfo().getName())) {
            // 获取数据中心信息
            AmazonInfo amazonInfo = (AmazonInfo) instanceInfo.getDataCenterInfo();
            // 获取数据中心元数据
            Map<String, String> metadata = amazonInfo.getMetadata();
            String availabilityZone = metadata.get(AmazonInfo.MetaDataKey.availabilityZone.getName());
            if (null != availabilityZone) {
                // 使用 zone 去获取 region信息 一般就是使用defaultZone 去获取region
                return azToRegionMapper.getRegionForAvailabilityZone(availabilityZone);
            }
        }

        return null;
    }

    public boolean isLocalRegion(@Nullable String instanceRegion) {
        return null == instanceRegion || instanceRegion.equals(localRegion); // no region == local
    }

    public String getLocalRegion() {
        return localRegion;
    }

    public AzToRegionMapper getAzToRegionMapper() {
        return azToRegionMapper;
    }
}
