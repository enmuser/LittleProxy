package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import java.util.Queue;

/**
 * <p>
 * Interface for classes that manage chained proxies.
 * </p>
 */
public interface ChainedProxyManager {

    /**
     * <p>
     * Based on the given httpRequest, add any {@link ChainedProxy}s to the list
     * that should be used to process the request. The downstream proxy will
     * attempt to connect to each of these in the order that they appear until
     * it successfully connects to one.
     * 根据给定的httpRequest，将所有{@link ChainedProxy}添加到列表中，
     * 该列表应用于处理请求。
     * 下游代理将尝试按照它们出现的顺序连接到它们中的每一个，直到成功连接到其中一个
     * </p>
     * 
     * <p>
     * To allow the proxy to fall back to a direct connection, you can add
     * {@link ChainedProxyAdapter#FALLBACK_TO_DIRECT_CONNECTION} to the end of
     * the list.
     * 要允许代理回退到直接连接，可以将{@link ChainedProxyAdapter＃FALLBACK_TO_DIRECT_CONNECTION}添加到列表的末尾
     * </p>
     * 
     * <p>
     * To keep the proxy from attempting any connection, leave the list blank.
     * This will cause the proxy to return a 502 response.
     * 为了防止代理尝试任何连接，请将列表留空。 这将导致代理返回502响应。
     * </p>
     * 
     * @param httpRequest
     * @param chainedProxies
     */
    void lookupChainedProxies(HttpRequest httpRequest,
            Queue<ChainedProxy> chainedProxies);
}