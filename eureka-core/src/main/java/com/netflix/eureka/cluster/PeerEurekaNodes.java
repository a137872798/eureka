package com.netflix.eureka.cluster;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.transport.JerseyReplicationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to manage lifecycle of a collection of {@link PeerEurekaNode}s.
 *
 * @author Tomasz Bak
 * 代表 所有的 eurekaServer 同级节点
 */
@Singleton
public class PeerEurekaNodes {

    private static final Logger logger = LoggerFactory.getLogger(PeerEurekaNodes.class);

    /**
     * 具备将请求发往所有同级节点的注册中心
     */
    protected final PeerAwareInstanceRegistry registry;
    protected final EurekaServerConfig serverConfig;
    protected final EurekaClientConfig clientConfig;
    protected final ServerCodecs serverCodecs;
    private final ApplicationInfoManager applicationInfoManager;

    /**
     * 内部实际维护的 一个 node 列表
     */
    private volatile List<PeerEurekaNode> peerEurekaNodes = Collections.emptyList();
    /**
     * 每个 eurekaNode 对应的 serviceUrl
     */
    private volatile Set<String> peerEurekaNodeUrls = Collections.emptySet();

    /**
     * 定时线程池
     */
    private ScheduledExecutorService taskExecutor;

    /**
     * 每个节点代表一个eurekaServer
     */
    @Inject
    public PeerEurekaNodes(
            PeerAwareInstanceRegistry registry, // 代表具备将请求发送到 同级节点的 eurekaServer 对象
            EurekaServerConfig serverConfig,    // 本机作为 eurekaServer 的配置对象
            EurekaClientConfig clientConfig,    // 本机作为 eurekaClient 的配置独享
            ServerCodecs serverCodecs,          // 编解码器
            ApplicationInfoManager applicationInfoManager  // 实例应用管理对象 在该对象首次被初始化时 内部的静态字段就会被赋值 之后每次调用 getInstance 都是返回同一个对象
            ) {
        this.registry = registry;
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.applicationInfoManager = applicationInfoManager;
    }

    public List<PeerEurekaNode> getPeerNodesView() {
        return Collections.unmodifiableList(peerEurekaNodes);
    }

    public List<PeerEurekaNode> getPeerEurekaNodes() {
        return peerEurekaNodes;
    }

    /**
     * 默认值为 -1
     * @return
     */
    public int getMinNumberOfAvailablePeers() {
        return serverConfig.getHealthStatusMinNumberOfAvailablePeers();
    }

    public void start() {
        taskExecutor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "Eureka-PeerNodesUpdater");
                        thread.setDaemon(true);
                        return thread;
                    }
                }
        );
        try {
            // 通过维护的所有对端节点url 更新节点信息  对端节点url 应该是从 config中获取的
            // 并生成对应的节点列表
            updatePeerEurekaNodes(resolvePeerUrls());
            Runnable peersUpdateTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        // 从配置文件中获取最新的 serviceUrl 信息设置到 node 中
                        updatePeerEurekaNodes(resolvePeerUrls());
                    } catch (Throwable e) {
                        logger.error("Cannot update the replica Nodes", e);
                    }

                }
            };
            // 开启定时任务
            taskExecutor.scheduleWithFixedDelay(
                    peersUpdateTask,
                    serverConfig.getPeerEurekaNodesUpdateIntervalMs(),
                    serverConfig.getPeerEurekaNodesUpdateIntervalMs(),
                    TimeUnit.MILLISECONDS
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (PeerEurekaNode node : peerEurekaNodes) {
            logger.info("Replica node URL:  {}", node.getServiceUrl());
        }
    }

    /**
     * 停止更新 node 节点的任务 并清空列表
     */
    public void shutdown() {
        taskExecutor.shutdown();
        List<PeerEurekaNode> toRemove = this.peerEurekaNodes;

        this.peerEurekaNodes = Collections.emptyList();
        this.peerEurekaNodeUrls = Collections.emptySet();

        for (PeerEurekaNode node : toRemove) {
            node.shutDown();
        }
    }

    /**
     * Resolve peer URLs.
     *
     * @return peer URLs with node's own URL filtered out
     * 解析其他节点的 url
     */
    protected List<String> resolvePeerUrls() {
        // 这个应该是本机实例信息 InstanceInfo
        InstanceInfo myInfo = applicationInfoManager.getInfo();
        // 根据 region 返回下面所有的 zone  如果找不到 会默认使用 defaultZone 去查找属性值  看来针对region zone 有实际含义的就代表是 AWS
        // 这里默认会返回多个zone 中的第一个
        String zone = InstanceInfo.getZone(clientConfig.getAvailabilityZones(clientConfig.getRegion()), myInfo);
        // 获取注册中心的路径 如果只有一个 zone 就返回该zone 的全部 url 否则 返回2个 zone 间所有url  第二个zone 代表多个可用zone 的最后一个
        List<String> replicaUrls = EndpointUtils
                .getDiscoveryServiceUrls(clientConfig, zone, new EndpointUtils.InstanceInfoBasedUrlRandomizer(myInfo));

        int idx = 0;
        while (idx < replicaUrls.size()) {
            if (isThisMyUrl(replicaUrls.get(idx))) {
                // 如果注册中心是自身 就移除掉
                replicaUrls.remove(idx);
            } else {
                idx++;
            }
        }
        // 仅返回 其他注册中心的url  注册中心url 为自身的都要移除掉
        return replicaUrls;
    }

    /**
     * Given new set of replica URLs, destroy {@link PeerEurekaNode}s no longer available, and
     * create new ones.
     *
     * @param newPeerUrls peer node URLs; this collection should have local node's URL filtered out
     *                    传入 其他注册中心的 url 并尝试更新节点
     */
    protected void updatePeerEurekaNodes(List<String> newPeerUrls) {
        if (newPeerUrls.isEmpty()) {
            logger.warn("The replica size seems to be empty. Check the route 53 DNS Registry");
            return;
        }

        // 代表 本次url中不存在 的其他url 应该被移除
        Set<String> toShutdown = new HashSet<>(peerEurekaNodeUrls);
        toShutdown.removeAll(newPeerUrls);
        // 代表本次url中 应该增加的部分  第一次调用该方法就是将全部url 设置到 toAdd中
        Set<String> toAdd = new HashSet<>(newPeerUrls);
        toAdd.removeAll(peerEurekaNodeUrls);

        // 代表注册中心节点没有发生变化 直接返回
        if (toShutdown.isEmpty() && toAdd.isEmpty()) { // No change
            return;
        }

        // Remove peers no long available
        // 代表新的节点列表 根据 toRemove 和 toAdd 进行调整
        List<PeerEurekaNode> newNodeList = new ArrayList<>(peerEurekaNodes);

        if (!toShutdown.isEmpty()) {
            logger.info("Removing no longer available peer nodes {}", toShutdown);
            int i = 0;
            while (i < newNodeList.size()) {
                PeerEurekaNode eurekaNode = newNodeList.get(i);
                if (toShutdown.contains(eurekaNode.getServiceUrl())) {
                    newNodeList.remove(i);
                    // 终止该节点 也就是 关闭 任务池线程
                    eurekaNode.shutDown();
                } else {
                    i++;
                }
            }
        }

        // Add new peers
        if (!toAdd.isEmpty()) {
            logger.info("Adding new peer nodes {}", toAdd);
            for (String peerUrl : toAdd) {
                // createPeerEurekaNode 根据url 创建一个新的节点
                newNodeList.add(createPeerEurekaNode(peerUrl));
            }
        }

        this.peerEurekaNodes = newNodeList;
        // 更新当前peer节点的 url
        this.peerEurekaNodeUrls = new HashSet<>(newPeerUrls);
    }

    /**
     * 通过 peerNode url 拉取节点信息
     * @param peerEurekaNodeUrl
     * @return
     */
    protected PeerEurekaNode createPeerEurekaNode(String peerEurekaNodeUrl) {
        // 创建一个 RelicationClient 对象 这里创建的是 JerseyReplicationClient 对象
        HttpReplicationClient replicationClient = JerseyReplicationClient.createReplicationClient(serverConfig, serverCodecs, peerEurekaNodeUrl);
        // 截取 host 信息
        String targetHost = hostFromUrl(peerEurekaNodeUrl);
        if (targetHost == null) {
            targetHost = "host";
        }
        // 创建对端节点对象 针对该对象发起的请求 会进入到 任务池中 并且 会发送给所有peerNode
        return new PeerEurekaNode(registry, targetHost, peerEurekaNodeUrl, replicationClient, serverConfig);
    }

    /**
     * @deprecated 2016-06-27 use instance version of {@link #isThisMyUrl(String)}
     *
     * Checks if the given service url contains the current host which is trying
     * to replicate. Only after the EIP binding is done the host has a chance to
     * identify itself in the list of replica nodes and needs to take itself out
     * of replication traffic.
     *
     * @param url the service url of the replica node that the check is made.
     * @return true, if the url represents the current node which is trying to
     *         replicate, false otherwise.
     */
    public static boolean isThisMe(String url) {
        InstanceInfo myInfo = ApplicationInfoManager.getInstance().getInfo();
        String hostName = hostFromUrl(url);
        return hostName != null && hostName.equals(myInfo.getHostName());
    }

    /**
     * Checks if the given service url contains the current host which is trying
     * to replicate. Only after the EIP binding is done the host has a chance to
     * identify itself in the list of replica nodes and needs to take itself out
     * of replication traffic.
     *
     * @param url the service url of the replica node that the check is made.
     * @return true, if the url represents the current node which is trying to
     *         replicate, false otherwise.
     */
    public boolean isThisMyUrl(String url) {
        // 返回服务端的 url  应该是说 本机既是服务端又是客户端吧
        final String myUrlConfigured = serverConfig.getMyUrl();
        if (myUrlConfigured != null) {
            return myUrlConfigured.equals(url);
        }
        // 判断是否是 实例自身的url
        return isInstanceURL(url, applicationInfoManager.getInfo());
    }
    
    /**
     * Checks if the given service url matches the supplied instance
     *
     * @param url the service url of the replica node that the check is made.
     * @param instance the instance to check the service url against
     * @return true, if the url represents the supplied instance, false otherwise.
     * 判断传入的url 是否跟 instance 的url 一致
     */
    public boolean isInstanceURL(String url, InstanceInfo instance) {
        // 截取 host
        String hostName = hostFromUrl(url);
        String myInfoComparator = instance.getHostName();
        // 如果是使用ip 注册
        if (clientConfig.getTransportConfig().applicationsResolverUseIp()) {
            myInfoComparator = instance.getIPAddr();
        }
        return hostName != null && hostName.equals(myInfoComparator);
    }

    public static String hostFromUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            logger.warn("Cannot parse service URI {}", url, e);
            return null;
        }
        return uri.getHost();
    }
}
