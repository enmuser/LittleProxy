package org.littleshoot.proxy;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Resolves host and port into an InetSocketAddress.
 * 将主机和端口解析为一个InetSocketAddress。
 */
public interface HostResolver {
    public InetSocketAddress resolve(String host, int port)
            throws UnknownHostException;
}
