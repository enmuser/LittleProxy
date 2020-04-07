package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.InetSocketAddress;

import javax.net.ssl.SSLSession;

/**
 * Adapter of {@link ActivityTracker} interface that provides default no-op
 * implementations of all methods.
 * {@link ActivityTracker}接口的适配器，提供所有方法的默认无操作实现
 */
public class ActivityTrackerAdapter implements ActivityTracker {

    @Override
    public void bytesReceivedFromClient(FlowContext flowContext,
            int numberOfBytes) {
    }

    @Override
    public void requestReceivedFromClient(FlowContext flowContext,
            HttpRequest httpRequest) {
    }

    @Override
    public void bytesSentToServer(FullFlowContext flowContext, int numberOfBytes) {
    }

    @Override
    public void requestSentToServer(FullFlowContext flowContext,
            HttpRequest httpRequest) {
    }

    @Override
    public void bytesReceivedFromServer(FullFlowContext flowContext,
            int numberOfBytes) {
    }

    @Override
    public void responseReceivedFromServer(FullFlowContext flowContext,
            HttpResponse httpResponse) {
    }

    @Override
    public void bytesSentToClient(FlowContext flowContext,
            int numberOfBytes) {
    }

    @Override
    public void responseSentToClient(FlowContext flowContext,
            HttpResponse httpResponse) {
    }

    @Override
    public void clientConnected(InetSocketAddress clientAddress) {
    }

    @Override
    public void clientSSLHandshakeSucceeded(InetSocketAddress clientAddress,
            SSLSession sslSession) {
    }

    @Override
    public void clientDisconnected(InetSocketAddress clientAddress,
            SSLSession sslSession) {
    }

}
