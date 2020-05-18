package com.netflix.eureka.cluster;

import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all replication tasks.
 */
abstract class ReplicationTask {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationTask.class);

    /**
     * 代表本次请求将会发往哪个节点
     */
    protected final String peerNodeName;
    /**
     * 复制的动作
     */
    protected final Action action;

    ReplicationTask(String peerNodeName, Action action) {
        this.peerNodeName = peerNodeName;
        this.action = action;
    }

    public abstract String getTaskName();

    public Action getAction() {
        return action;
    }

    public abstract EurekaHttpResponse<?> execute() throws Throwable;

    public void handleSuccess() {
    }

    public void handleFailure(int statusCode, Object responseEntity) throws Throwable {
        logger.warn("The replication of task {} failed with response code {}", getTaskName(), statusCode);
    }
}
