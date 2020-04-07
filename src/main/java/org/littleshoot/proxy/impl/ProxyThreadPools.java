package org.littleshoot.proxy.impl;

import com.google.common.collect.ImmutableList;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.nio.channels.spi.SelectorProvider;
import java.util.List;

/**
 * Encapsulates the thread pools used by the proxy. Contains the acceptor thread pool as well as the
 * client-to-proxy and proxy-to-server thread pools.
 * 封装代理使用的线程池。
 * 包含接受者线程池以及客户端到代理和代理到服务器的线程池
 */
public class ProxyThreadPools {
  /**
   * These {@link EventLoopGroup}s accept incoming connections to the proxies. A different
   * EventLoopGroup is used for each TransportProtocol, since these have to be configured
   * differently.
   * 这些{@link EventLoopGroup}接受到代理的传入连接。
   * 每个TransportProtocol使用不同的EventLoopGroup，因为必须对它们进行不同的配置
   */
  private final NioEventLoopGroup clientToProxyAcceptorPool;

  /**
   * These {@link EventLoopGroup}s process incoming requests to the proxies. A different
   * EventLoopGroup is used for each TransportProtocol, since these have to be configured
   * differently.
   * 这些{@link EventLoopGroup}处理向代理服务器的传入请求。
   * 每个TransportProtocol使用不同的EventLoopGroup，因为必须对它们进行不同的配置。
   */
  private final NioEventLoopGroup clientToProxyWorkerPool;

  /**
   * These {@link EventLoopGroup}s are used for making outgoing connections to servers. A different
   * EventLoopGroup is used for each TransportProtocol, since these have to be configured
   * differently.
   *
   * 这些{@link EventLoopGroup}用于建立到服务器的传出连接。
   * 每个TransportProtocol使用不同的EventLoopGroup，因为必须对它们进行不同的配置
   */
  private final NioEventLoopGroup proxyToServerWorkerPool;

  public ProxyThreadPools(
      SelectorProvider selectorProvider,
      int incomingAcceptorThreads,
      int incomingWorkerThreads,
      int outgoingWorkerThreads,
      String serverGroupName,
      int serverGroupId) {
    clientToProxyAcceptorPool =
        new NioEventLoopGroup(
            incomingAcceptorThreads,
            new CategorizedThreadFactory(serverGroupName, "ClientToProxyAcceptor", serverGroupId),
            selectorProvider);

    clientToProxyWorkerPool =
        new NioEventLoopGroup(
            incomingWorkerThreads,
            new CategorizedThreadFactory(serverGroupName, "ClientToProxyWorker", serverGroupId),
            selectorProvider);
    clientToProxyWorkerPool.setIoRatio(90);

    proxyToServerWorkerPool =
        new NioEventLoopGroup(
            outgoingWorkerThreads,
            new CategorizedThreadFactory(serverGroupName, "ProxyToServerWorker", serverGroupId),
            selectorProvider);
    proxyToServerWorkerPool.setIoRatio(90);
  }

  /** Returns all event loops (acceptor and worker thread pools) in this pool.
   * 返回此池中的所有事件循环（接受者线程和工作线程池）
   */
  public List<EventLoopGroup> getAllEventLoops() {
    return ImmutableList.<EventLoopGroup>of(
        clientToProxyAcceptorPool, clientToProxyWorkerPool, proxyToServerWorkerPool);
  }

  public NioEventLoopGroup getClientToProxyAcceptorPool() {
    return clientToProxyAcceptorPool;
  }

  public NioEventLoopGroup getClientToProxyWorkerPool() {
    return clientToProxyWorkerPool;
  }

  public NioEventLoopGroup getProxyToServerWorkerPool() {
    return proxyToServerWorkerPool;
  }
}
