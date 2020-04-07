package org.littleshoot.proxy;

import org.littleshoot.proxy.impl.ClientToProxyConnection;
import org.littleshoot.proxy.impl.ProxyToServerConnection;

/**
 * Extension of {@link FlowContext} that provides additional information (which
 * we know after actually processing the request from the client).
 *
 * {@link FlowContext}的扩展提供了其他信息（在实际处理来自客户端的请求之后，我们知道）
 */
public class FullFlowContext extends FlowContext {
    private final String serverHostAndPort;
    private final ChainedProxy chainedProxy;

    public FullFlowContext(ClientToProxyConnection clientConnection,
            ProxyToServerConnection serverConnection) {
        super(clientConnection);
        this.serverHostAndPort = serverConnection.getServerHostAndPort();
        this.chainedProxy = serverConnection.getChainedProxy();
    }

    /**
     * The host and port for the server (i.e. the ultimate endpoint).
     * 
     * @return
     */
    public String getServerHostAndPort() {
        return serverHostAndPort;
    }

    /**
     * The chained proxy (if proxy chaining).
     * 
     * @return
     */
    public ChainedProxy getChainedProxy() {
        return chainedProxy;
    }

}
