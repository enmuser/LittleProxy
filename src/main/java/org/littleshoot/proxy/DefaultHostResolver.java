package org.littleshoot.proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Default implementation of {@link HostResolver} that just uses
 * {@link InetAddress#getByName(String)}.
 *
 * {@link HostResolver}的默认实现，仅使用{@link InetAddress＃getByName（String）}。
 */
public class DefaultHostResolver implements HostResolver {
    @Override
    public InetSocketAddress resolve(String host, int port)
            throws UnknownHostException {
        InetAddress addr = InetAddress.getByName(host);
        return new InetSocketAddress(addr, port);
    }
}
