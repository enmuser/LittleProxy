package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;

import java.net.InetSocketAddress;

/**
 * <p>
 * Encapsulates information needed to connect to a chained proxy.
 * 封装连接到链接代理所需的信息
 * </p>
 * 
 * <p>
 * Sub-classes may wish to extend {@link ChainedProxyAdapter} for sensible
 * defaults.
 * 子类可能希望扩展{@link ChainedProxyAdapter}以获得合理的默认值。
 * </p>
 */
public interface ChainedProxy extends SslEngineSource {
    /**
     * Return the {@link InetSocketAddress} for connecting to the chained proxy.
     * Returning null indicates that we won't chain.
     * 返回{@link InetSocketAddress}以连接到链接的代理。 返回null表示我们不会链接。
     * 
     * @return The Chain Proxy with Host and Port.
     */
    InetSocketAddress getChainedProxyAddress();

    /**
     * (Optional) ensure that the connection is opened from a specific local
     * address (useful when doing NAT traversal).
     *
     * （可选）确保从特定的本地地址打开连接（在进行NAT遍历时很有用）
     * 
     * @return
     */
    InetSocketAddress getLocalAddress();

    /**
     * Tell LittleProxy what kind of TransportProtocol to use to communicate
     * with the chained proxy.
     *
     * 告诉LittleProxy使用哪种TransportProtocol与链式代理进行通讯
     * 
     * @return
     */
    TransportProtocol getTransportProtocol();

    /**
     * Implement this method to tell LittleProxy whether or not to encrypt
     * connections to the chained proxy for the given request. If true,
     * LittleProxy will call {@link SslEngineSource#newSslEngine()} to obtain an
     * SSLContext used by the downstream proxy.
     * 
     * @return true of the connection to the chained proxy should be encrypted
     */
    boolean requiresEncryption();

    /**
     * Filters requests on their way to the chained proxy.
     * 
     * @param httpObject
     */
    void filterRequest(HttpObject httpObject);

    /**
     * Called to let us know that connecting to this proxy succeeded.
     */
    void connectionSucceeded();

    /**
     * Called to let us know that connecting to this proxy failed.
     * 
     * @param cause
     *            exception that caused this failure (may be null)
     */
    void connectionFailed(Throwable cause);

    /**
     * Called to let us know that we were disconnected.
     */
    void disconnected();
}
