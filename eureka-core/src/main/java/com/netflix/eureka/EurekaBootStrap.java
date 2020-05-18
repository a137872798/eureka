/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.Date;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.AwsInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that kick starts the eureka server.
 *
 * <p>
 * The eureka server is configured by using the configuration
 * {@link EurekaServerConfig} specified by <em>eureka.server.props</em> in the
 * classpath.  The eureka client component is also initialized by using the
 * configuration {@link EurekaInstanceConfig} specified by
 * <em>eureka.client.props</em>. If the server runs in the AWS cloud, the eureka
 * server binds it to the elastic ip as specified.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim, David Liu
 * 该对象作为整个 eureka启动的引导程序 它实现于 ServletContextListener 接口
 */
public class EurekaBootStrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(EurekaBootStrap.class);

    private static final String TEST = "test";

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    private static final String CLOUD = "cloud";
    private static final String DEFAULT = "default";

    private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

    private static final String EUREKA_DATACENTER = "eureka.datacenter";

    /**
     * 该对象作为一个统一的协调工具 内部包含多个核心组件
     */
    protected volatile EurekaServerContext serverContext;
    /**
     * AWS 相关先不看
     */
    protected volatile AwsBinder awsBinder;

    /**
     * 这个client 实际上就是 DiscoveryClient  根据配置的情况 定期从注册中心拉取数据 以及定期为自身续约
     */
    private EurekaClient eurekaClient;

    /**
     * Construct a default instance of Eureka boostrap
     */
    public EurekaBootStrap() {
        this(null);
    }
    
    /**
     * Construct an instance of eureka bootstrap with the supplied eureka client
     * 
     * @param eurekaClient the eureka client to bootstrap
     *                     注意这里可以通过一个client来初始化
     */
    public EurekaBootStrap(EurekaClient eurekaClient) {
        this.eurekaClient = eurekaClient;
    }

    /**
     * Initializes Eureka, including syncing up with other Eureka peers and publishing the registry.
     *
     * @see
     * javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            // 初始化eureka环境  推测是这样 在配置中心中还包含类似namespace的概念 当启动eureka时从指定的命名空间中获取Configuration 之后每个 配置的属性都是
            // 基于该命名空间的
            initEurekaEnvironment();
            // 初始化 eurekaServerContext 对象
            initEurekaServerContext();

            ServletContext sc = event.getServletContext();
            // 将eurekaServerContext 设置到 servletContext 中便于随时能访问到
            sc.setAttribute(EurekaServerContext.class.getName(), serverContext);
        } catch (Throwable e) {
            logger.error("Cannot bootstrap eureka server :", e);
            throw new RuntimeException("Cannot bootstrap eureka server :", e);
        }
    }

    /**
     * Users can override to initialize the environment themselves.
     * 当感知到servlet环境被搭建时 初始化  eureka环境
     */
    protected void initEurekaEnvironment() throws Exception {
        logger.info("Setting the eureka configuration..");

        // 获取 dataCenter 信息
        String dataCenter = ConfigurationManager.getConfigInstance().getString(EUREKA_DATACENTER);
        if (dataCenter == null) {
            // 设置成 default 也就是默认值
            logger.info("Eureka data center value eureka.datacenter is not set, defaulting to default");
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, DEFAULT);
        } else {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, dataCenter);
        }
        // 没有设置环境的情况下 默认使用 test
        String environment = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT);
        if (environment == null) {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, TEST);
            logger.info("Eureka environment value eureka.environment is not set, defaulting to test");
        }
    }

    /**
     * init hook for server context. Override for custom logic.
     * eureka上下文对象本身包含了一系列启动必备的组件
     */
    protected void initEurekaServerContext() throws Exception {
        // 这里简单的理解为就是通过某种方式 获取到配置中心的配置
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();

        // For backward compatibility
        // 注册了有关XML/JSON的转换器
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);

        logger.info("Initializing the eureka client...");
        logger.info(eurekaServerConfig.getJsonCodecName());
        // 写入编解码器 负责将数据体变成 JSON/XML 格式  (压缩及非压缩)
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);

        ApplicationInfoManager applicationInfoManager = null;

        // 可以选择在初始化时 传入client  或者不传入  如果不传入会在此时创建
        if (eurekaClient == null) {
            // 初始化本节点作为eureka-client的相关配置
            EurekaInstanceConfig instanceConfig = isCloud(ConfigurationManager.getDeploymentContext())
                    ? new CloudInstanceConfig()
                    : new MyDataCenterInstanceConfig();

            // manager对象负责管理instance本身
            applicationInfoManager = new ApplicationInfoManager(
                    instanceConfig,
                    // 通过config中相关属性 初始化instance 对象
                    new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());

            // 读取client的配置
            EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();
            // 初始化服务发现client  定期从注册中心拉取最新数据  注册中心地址的选择机制是这样  先根据client所在的 region
            // 找到region下所有的zone  并且拉取zone下所有节点url 作为一个备选地址  之后有一个地区亲和的对象尽可能的将同一zone的地址排在前面
            // 在往上是一个异步解析器 会定期从配置中心拉取最新地址列表
            eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
        } else {
            applicationInfoManager = eurekaClient.getApplicationInfoManager();
        }

        // 默认情况下本节点不仅作为  eureka-client 向注册中心注册实例 同时还作为一个eureka-server 接收其他节点的注册请求
        PeerAwareInstanceRegistry registry;
        // 亚马逊云相关的忽略
        if (isAws(applicationInfoManager.getInfo())) {
            registry = new AwsInstanceRegistry(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
            awsBinder = new AwsBinderDelegate(eurekaServerConfig, eurekaClient.getEurekaClientConfig(), registry, applicationInfoManager);
            awsBinder.start();
        } else {
            // 开始初始化 集群同步对象  往该节点发送的请求根据情况会自动转发到集群内其他节点
            registry = new PeerAwareInstanceRegistryImpl(
                    // 该对象内 维护了 eureka-server 的信息
                    eurekaServerConfig,
                    // 获取 eureka-client 的相关信息
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
        }

        // 此时 nodes 内部的 List<node> 还没有初始化
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(),
                serverCodecs,
                applicationInfoManager
        );

        // 该对象负责统一管理 eureka相关组件的生命周期
        serverContext = new DefaultEurekaServerContext(
                eurekaServerConfig,
                serverCodecs,
                registry,
                peerEurekaNodes,
                applicationInfoManager
        );

        // 设置到静态变量中
        EurekaServerContextHolder.initialize(serverContext);

        serverContext.initialize();
        logger.info("Initialized server context");

        // Copy registry from neighboring eureka node
        // 当注册中心启动完成时第一步是想到从本集群中其他节点同步数据
        int registryCount = registry.syncUp();
        // 将本节点标记成可用 也就是能被集群访问到  否则 当尝试从该节点获取实例信息时很可能返回false  (还要看那个empty标识设置的时间是否超过一个等待时间)
        registry.openForTraffic(applicationInfoManager, registryCount);

        // Register all monitoring statistics.
        EurekaMonitors.registerAllStats();
    }

    /**
     * @param registry
     * @param eurekaServerConfig
     * @param eurekaClientConfig
     * @param serverCodecs
     * @param applicationInfoManager
     * @return
     */
    protected PeerEurekaNodes getPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig eurekaServerConfig, EurekaClientConfig eurekaClientConfig, ServerCodecs serverCodecs, ApplicationInfoManager applicationInfoManager) {
        PeerEurekaNodes peerEurekaNodes = new PeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClientConfig,
                serverCodecs,
                applicationInfoManager
        );
        
        return peerEurekaNodes;
    }

    /**
     * Handles Eureka cleanup, including shutting down all monitors and yielding all EIPs.
     * 监听到销毁事件时
     *
     * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            logger.info("{} Shutting down Eureka Server..", new Date());
            ServletContext sc = event.getServletContext();
            sc.removeAttribute(EurekaServerContext.class.getName());

            // 销毁上下文对象和环境对象
            destroyEurekaServerContext();
            destroyEurekaEnvironment();

        } catch (Throwable e) {
            logger.error("Error shutting down eureka", e);
        }
        logger.info("{} Eureka Service is now shutdown...", new Date());
    }

    /**
     * Server context shutdown hook. Override for custom logic
     */
    protected void destroyEurekaServerContext() throws Exception {
        EurekaMonitors.shutdown();
        if (awsBinder != null) {
            awsBinder.shutdown();
        }
        if (serverContext != null) {
            serverContext.shutdown();
        }
    }

    /**
     * Users can override to clean up the environment themselves.
     */
    protected void destroyEurekaEnvironment() throws Exception {

    }

    /**
     * 判断该实例是否是 AWS 就是看 dataCenter 是否是 amazon  一般是 myDataCenter 也就是 依赖于 本地配置文件
     * @param selfInstanceInfo
     * @return
     */
    protected boolean isAws(InstanceInfo selfInstanceInfo) {
        boolean result = DataCenterInfo.Name.Amazon == selfInstanceInfo.getDataCenterInfo().getName();
        logger.info("isAws returned {}", result);
        return result;
    }

    protected boolean isCloud(DeploymentContext deploymentContext) {
        logger.info("Deployment datacenter is {}", deploymentContext.getDeploymentDatacenter());
        return CLOUD.equals(deploymentContext.getDeploymentDatacenter());
    }
}
