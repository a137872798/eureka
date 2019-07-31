package com.netflix.eureka.cluster.protocol;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.netflix.discovery.provider.Serializer;

/**
 * The jersey resource class that generates the replication batch response.
 * 针对复制任务的返回值
 */
@Serializer("jackson") // For backwards compatibility with DiscoveryJerseyProvider
public class ReplicationListResponse {
    /**
     * 复制任务中 针对某个实例的返回值对象 看来复制任务会访问多个节点 并返回结果
     */
    private List<ReplicationInstanceResponse> responseList;

    public ReplicationListResponse() {
        this.responseList = new ArrayList<ReplicationInstanceResponse>();
    }

    @JsonCreator
    public ReplicationListResponse(@JsonProperty("responseList") List<ReplicationInstanceResponse> responseList) {
        this.responseList = responseList;
    }

    public List<ReplicationInstanceResponse> getResponseList() {
        return responseList;
    }

    /**
     * 添加响应对象
     * @param singleResponse
     */
    public void addResponse(ReplicationInstanceResponse singleResponse) {
        responseList.add(singleResponse);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        ReplicationListResponse that = (ReplicationListResponse) o;

        return !(responseList != null ? !responseList.equals(that.responseList) : that.responseList != null);

    }

    @Override
    public int hashCode() {
        return responseList != null ? responseList.hashCode() : 0;
    }
}
