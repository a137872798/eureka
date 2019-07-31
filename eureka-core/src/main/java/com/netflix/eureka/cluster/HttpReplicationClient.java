package com.netflix.eureka.cluster;

import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.cluster.protocol.ReplicationList;
import com.netflix.eureka.cluster.protocol.ReplicationListResponse;
import com.netflix.eureka.resources.ASGResource.ASGStatus;

/**
 * 具备将某一动作 同时 作用给多个节点的能力
 * @author Tomasz Bak
 */
public interface HttpReplicationClient extends EurekaHttpClient {

    EurekaHttpResponse<Void> statusUpdate(String asgName, ASGStatus newStatus);

    /**
     * 批量更新
     * @param replicationList
     * @return
     */
    EurekaHttpResponse<ReplicationListResponse> submitBatchUpdates(ReplicationList replicationList);

}
