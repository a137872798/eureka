package com.netflix.eureka.registry;

import com.netflix.appinfo.EurekaAccept;
import com.netflix.eureka.Version;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * 这些信息确定了 返回给 client的某种状态    缓存是按这个维度划分的
 */
public class Key {

    /**
     * 对应响应体的类型
     */
    public enum KeyType {
        JSON, XML
    }

    /**
     * An enum to define the entity that is stored in this cache for this key.
     * 代表本次存储的数据是普通的应用实例数据 还是VIP 数据 或者 SVIP(在VIP的层面增加了security)
     */
    public enum EntityType {
        Application, VIP, SVIP
    }

    private final String entityName;
    /**
     * 允许为 缓存键设置地区信息
     */
    private final String[] regions;
    private final KeyType requestType;
    /**
     * 本次请求的 版本 默认 是 V2  这个属性是 eureka框架版本相关的 与服务无关
     */
    private final Version requestVersion;
    private final String hashKey;
    private final EntityType entityType;
    /**
     * 代表缓存的数据是否要压缩
     */
    private final EurekaAccept eurekaAccept;

    public Key(EntityType entityType, String entityName, KeyType type, Version v, EurekaAccept eurekaAccept) {
        this(entityType, entityName, type, v, eurekaAccept, null);
    }

    public Key(EntityType entityType, String entityName, KeyType type, Version v, EurekaAccept eurekaAccept, @Nullable String[] regions) {
        this.regions = regions;
        this.entityType = entityType;
        this.entityName = entityName;
        this.requestType = type;
        this.requestVersion = v;
        this.eurekaAccept = eurekaAccept;
        hashKey = this.entityType + this.entityName + (null != this.regions ? Arrays.toString(this.regions) : "")
                + requestType.name() + requestVersion.name() + this.eurekaAccept.name();
    }

    public String getName() {
        return entityName;
    }

    public String getHashKey() {
        return hashKey;
    }

    public KeyType getType() {
        return requestType;
    }

    public Version getVersion() {
        return requestVersion;
    }

    public EurekaAccept getEurekaAccept() {
        return eurekaAccept;
    }

    public EntityType getEntityType() {
        return entityType;
    }

    public boolean hasRegions() {
        return null != regions && regions.length != 0;
    }

    public String[] getRegions() {
        return regions;
    }

    public Key cloneWithoutRegions() {
        return new Key(entityType, entityName, requestType, requestVersion, eurekaAccept);
    }

    @Override
    public int hashCode() {
        String hashKey = getHashKey();
        return hashKey.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof Key) {
            return getHashKey().equals(((Key) other).getHashKey());
        } else {
            return false;
        }
    }

    public String toStringCompact() {
        StringBuilder sb = new StringBuilder();
        sb.append("{name=").append(entityName).append(", type=").append(entityType).append(", format=").append(requestType);
        if(regions != null) {
            sb.append(", regions=").append(Arrays.toString(regions));
        }
        sb.append('}');
        return sb.toString();
    }
}
