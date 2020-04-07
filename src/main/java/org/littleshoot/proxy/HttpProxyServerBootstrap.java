package org.littleshoot.proxy;

import org.littleshoot.proxy.impl.ThreadPoolConfiguration;

import java.net.InetSocketAddress;

/**
 * Configures and starts an {@link HttpProxyServer}. The HttpProxyServer is
 * built using {@link #start()}. Sensible defaults are available for all
 * parameters such that {@link #start()} could be called immediately if you
 * wish.
 */
public interface HttpProxyServerBootstrap {

    /**
     * <p>
     * Give the server a name (used for naming threads, useful for logging).
     * </p>
     * 
     * <p>
     * Default = LittleProxy
     * </p>
     * 
     * @param name
     * @return
     */
    HttpProxyServerBootstrap withName(String name);

    /**
     * <p>
     * Specify the {@link TransportProtocol} to use for incoming connections.
     * </p>
     * 
     * <p>
     * Default = TCP
     * </p>
     * 
     * @param transportProtocol
     * @return
     */
    HttpProxyServerBootstrap withTransportProtocol(
            TransportProtocol transportProtocol);

    /**
     * <p>
     * Listen for incoming connections on the given address.
     * </p>
     * 
     * <p>
     * Default = [bound ip]:8080
     * </p>
     * 
     * @param address
     * @return
     */
    HttpProxyServerBootstrap withAddress(InetSocketAddress address);

    /**
     * <p>
     * Listen for incoming connections on the given port.
     * </p>
     * 
     * <p>
     * Default = 8080
     * </p>
     * 
     * @param port
     * @return
     */
    HttpProxyServerBootstrap withPort(int port);

    /**
     * <p>
     * Specify whether or not to only allow local connections.
     * </p>
     * 
     * <p>
     * Default = true
     * </p>
     * 
     * @param allowLocalOnly
     * @return
     */
    HttpProxyServerBootstrap withAllowLocalOnly(boolean allowLocalOnly);

    /**
     * This method has no effect and will be removed in a future release.
     * @deprecated use {@link #withNetworkInterface(InetSocketAddress)} to avoid listening on all local addresses
     */
    @Deprecated
    HttpProxyServerBootstrap withListenOnAllAddresses(boolean listenOnAllAddresses);

    /**
     * <p>
     * Specify an {@link SslEngineSource} to use for encrypting inbound
     * connections. Enabling this will enable SSL client authentication
     * by default (see {@link #withAuthenticateSslClients(boolean)})
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * <p>
     * Note - This and {@link #withManInTheMiddle(MitmManager)} are
     * mutually exclusive.
     * </p>
     * 
     * @param sslEngineSource
     * @return
     */
    HttpProxyServerBootstrap withSslEngineSource(
            SslEngineSource sslEngineSource);

    /**
     * <p>
     * Specify whether or not to authenticate inbound SSL clients (only applies
     * if {@link #withSslEngineSource(SslEngineSource)} has been set).
     * </p>
     * 
     * <p>
     * Default = true
     * </p>
     * 
     * @param authenticateSslClients
     * @return
     */
    HttpProxyServerBootstrap withAuthenticateSslClients(
            boolean authenticateSslClients);

    /**
     * <p>
     * Specify a {@link ProxyAuthenticator} to use for doing basic HTTP
     * authentication of clients.
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * @param proxyAuthenticator
     * @return
     */
    HttpProxyServerBootstrap withProxyAuthenticator(
            ProxyAuthenticator proxyAuthenticator);

    /**
     * <p>
     * Specify a {@link ChainedProxyManager} to use for chaining requests to
     * another proxy.
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * @param chainProxyManager
     * @return
     */
    HttpProxyServerBootstrap withChainProxyManager(
            ChainedProxyManager chainProxyManager);

    /**
     * <p>
     * Specify an {@link MitmManager} to use for making this proxy act as an SSL
     * man in the middle
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * <p>
     * Note - This and {@link #withSslEngineSource(SslEngineSource)} are
     * mutually exclusive.
     * </p>
     * 
     * @param mitmManager
     * @return
     */
    HttpProxyServerBootstrap withManInTheMiddle(
            MitmManager mitmManager);

    /**
     * <p>
     * Specify a {@link HttpFiltersSource} to use for filtering requests and/or
     * responses through this proxy.
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * @param filtersSource
     * @return
     */
    HttpProxyServerBootstrap withFiltersSource(
            HttpFiltersSource filtersSource);

    /**
     * <p>
     * Specify whether or not to use secure DNS lookups for outbound
     * connections.
     * </p>
     * 
     * <p>
     * Default = false
     * </p>
     * 
     * @param useDnsSec
     * @return
     */
    HttpProxyServerBootstrap withUseDnsSec(
            boolean useDnsSec);

    /**
     * <p>
     * Specify whether or not to run this proxy as a transparent proxy.
     * </p>
     * 
     * <p>
     * Default = false
     * </p>
     * 
     * @param transparent
     * @return
     */
    HttpProxyServerBootstrap withTransparent(
            boolean transparent);

    /**
     * <p>
     * Specify the timeout after which to disconnect idle connections, in
     * seconds.
     * </p>
     * 
     * <p>
     * Default = 70
     * </p>
     * 
     * @param idleConnectionTimeout
     * @return
     */
    HttpProxyServerBootstrap withIdleConnectionTimeout(
            int idleConnectionTimeout);

    /**
     * <p>
     * Specify the timeout for connecting to the upstream server on a new
     * connection, in milliseconds.
     * </p>
     * 
     * <p>
     * Default = 40000
     * </p>
     * 
     * @param connectTimeout
     * @return
     */
    HttpProxyServerBootstrap withConnectTimeout(
            int connectTimeout);

    /**
     * Specify a custom {@link HostResolver} for resolving server addresses.
     * 
     * @param serverResolver
     * @return
     */
    HttpProxyServerBootstrap withServerResolver(HostResolver serverResolver);

    /**
     * <p>
     * Add an {@link ActivityTracker} for tracking activity in this proxy.
     * 添加一个{@link ActivityTracker}来跟踪此代理中的活动
     * </p>
     * 
     * @param activityTracker
     * @return
     */
    HttpProxyServerBootstrap plusActivityTracker(ActivityTracker activityTracker);

    /**
     * <p>
     * Specify the read and/or write bandwidth throttles for this proxy server. 0 indicates not throttling.
     * 指定此代理服务器的读取和/或写入带宽限制。 0表示不节流
     * </p>
     * @param readThrottleBytesPerSecond
     * @param writeThrottleBytesPerSecond
     * @return
     */
    HttpProxyServerBootstrap withThrottling(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond);

    /**
     * All outgoing-communication of the proxy-instance is goin' to be routed via the given network-interface
     * 代理实例的所有传出通信都将通过给定的网络接口进行路由
     *
     * @param inetSocketAddress to be used for outgoing communication
     */
    HttpProxyServerBootstrap withNetworkInterface(InetSocketAddress inetSocketAddress);
    
    HttpProxyServerBootstrap withMaxInitialLineLength(int maxInitialLineLength);
    
    HttpProxyServerBootstrap withMaxHeaderSize(int maxHeaderSize);
    
    HttpProxyServerBootstrap withMaxChunkSize(int maxChunkSize);

  /**
   * When true, the proxy will accept requests that appear to be directed at an origin server (i.e.
   * the URI in the HTTP request will contain an origin-form, rather than an absolute-form, as
   * specified in RFC 7230, section 5.3). This is useful when the proxy is acting as a
   * gateway/reverse proxy. <b>Note:</b> This feature should not be enabled when running as a
   * forward proxy; doing so may cause an infinite loop if the client requests the URI of the proxy.
   *
   * <p>设置为true时，代理服务器将接受似乎定向到原始服务器的请求（即，HTTP请求中的URI将包含原始形式，
   * 而不是RFC 7230第5.3节中指定的绝对形式）。
   * 当代理服务器充当网关/反向代理服务器时，这很有用。
   * <b>注意：</ b>作为正向代理运行时，不应启用此功能。 如果客户端请求代理的URI，这样做可能会导致无限循环。
   *
   * @param allowRequestToOriginServer when true, the proxy will accept origin-form HTTP requests
   */
  HttpProxyServerBootstrap withAllowRequestToOriginServer(boolean allowRequestToOriginServer);

    /**
     * Sets the alias to use when adding Via headers to incoming and outgoing HTTP messages. The alias may be any
     * pseudonym, or if not specified, defaults to the hostname of the local machine. See RFC 7230, section 5.7.1.
     * 设置将Via标头添加到传入和传出HTTP消息时使用的别名。
     * 别名可以是任何假名，或者，如果未指定，则默认为本地计算机的主机名。
     * 请参阅RFC 7230，第5.7.1节。
     *
     * @param alias the pseudonym to add to Via headers
     */
    HttpProxyServerBootstrap withProxyAlias(String alias);

    /**
     * <p>
     * Build and starts the server.
     * 构建并启动服务器
     * </p>
     *
     * @return the newly built and started server
     */
    HttpProxyServer start();

    /**
     * Set the configuration parameters for the proxy's thread pools.
     * 设置代理的线程池的配置参数
     *
     * @param configuration thread pool configuration
     * @return proxy server bootstrap for chaining
     */
    HttpProxyServerBootstrap withThreadPoolConfiguration(ThreadPoolConfiguration configuration);
}