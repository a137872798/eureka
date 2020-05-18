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
 * 该对象负责管理 所有PeerEurekaNode
 */
@Singleton
public class PeerEurekaNodes {

    private static final Logger logger = LoggerFactory.getLogger(PeerEurekaNodes.class);

    /**
     * 具备将请求发往所有同级节点的注册中心
     */
    protected final PeerAwareInstanceRegistry registry;

    // 每个eureka启动时 即会将自身作为client 也会作为server

    protected final EurekaServerConfig serverConfig;
    protected final EurekaClientConfig clientConfig;
    // 服务端编解码器
    protected final ServerCodecs serverCodecs;
    // 该对象内 包含一个instance 对应本节点作为 eureka-client的实例
    private final ApplicationInfoManager applicationInfoManager;

    // Nodes 本身代表某个eureka-server 集群内的所有节点

    /**
     * 内部实际维护的 一个 node 列表  不包含本节点
     */
    private volatile List<PeerEurekaNode> peerEurekaNodes = Collections.emptyList();
    /**
     * 每个 eurekaNode 对应的 serviceUrl   不包含本节点地址
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
            ApplicationInfoManager applicationInfoManager
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
     * 代表该对象认为集群内有多少能通信的节点 才认为是健康状态  (基于AP 的实现 节点越多就越不容易发生数据丢失)
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
            // 首次先从配置文件中读取本集群 (同一个region下所有实例认为在同一个集群  同时一个region下又有多个zone  默认情况下会采用地区亲和特性
            // 返回的一组url列表 会将同一zone的serviceUrl放在前面)
            // 注意这里node 中不包含本地址
            updatePeerEurekaNodes(resolvePeerUrls());

            // 该任务会定期拉取数据并更新节点
            Runnable peersUpdateTask = new Runnable() {
                @Override
                public void run() {
                    try {
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
     * 找到集群内其他eureka-server的地址
     */
    protected List<String> resolvePeerUrls() {
        // 获取本节点对应的instance
        InstanceInfo myInfo = applicationInfoManager.getInfo();

        // 默认使用该region下第一个zone
        String zone = InstanceInfo.getZone(clientConfig.getAvailabilityZones(clientConfig.getRegion()), myInfo);
        // 找到该region下所有 eureka-server的地址  如果采用zone亲和策略 那么前面的url都是同一zone的
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
        return replicaUrls;
    }

    /**
     * Given new set of replica URLs, destroy {@link PeerEurekaNode}s no longer available, and
     * create new ones.
     *
     * @param newPeerUrls peer node URLs; this collection should have local node's URL filtered out
     *                    刷新集群内服务列表 注意不包含本节点地址
     */
    protected void updatePeerEurekaNodes(List<String> newPeerUrls) {
        if (newPeerUrls.isEmpty()) {
            logger.warn("The replica size seems to be empty. Check the route 53 DNS Registry");
            return;
        }

        // 找到本次不存在的地址 它们将会被移除
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
     * 将同一region下 其他zone转换成一个node
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
        // 注意 所有PeerEurekaNode 共用一个registry
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
