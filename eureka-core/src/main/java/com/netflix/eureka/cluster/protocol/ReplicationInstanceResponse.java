package com.netflix.eureka.cluster.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.netflix.appinfo.InstanceInfo;

/**
 * The jersey resource class that generates the replication indivdiual response.
 * 针对某个复制请求的响应结果
 */
public class ReplicationInstanceResponse {

    /**
     * 结果状态码
     */
    private final int statusCode;
    /**
     * 响应实体
     */
    private final InstanceInfo responseEntity;

    /**
     * 该对象通过json 字符串初始化
     * @param statusCode
     * @param responseEntity
     */
    @JsonCreator
    public ReplicationInstanceResponse(
            @JsonProperty("statusCode") int statusCode,
            @JsonProperty("responseEntity") InstanceInfo responseEntity) {
        this.statusCode = statusCode;
        this.responseEntity = responseEntity;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public InstanceInfo getResponseEntity() {
        return responseEntity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        ReplicationInstanceResponse that = (ReplicationInstanceResponse) o;

        if (statusCode != that.statusCode)
            return false;
        if (responseEntity != null ? !responseEntity.equals(that.responseEntity) : that.responseEntity != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = statusCode;
        result = 31 * result + (responseEntity != null ? responseEntity.hashCode() : 0);
        return result;
    }

    public static final class Builder {

        private int statusCode;
        private InstanceInfo responseEntity;

        public Builder setStatusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Builder setResponseEntity(InstanceInfo entity) {
            this.responseEntity = entity;
            return this;
        }

        public ReplicationInstanceResponse build() {
            return new ReplicationInstanceResponse(statusCode, responseEntity);
        }
    }
}
