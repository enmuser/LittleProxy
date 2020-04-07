package org.littleshoot.proxy.test;

import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

public class LittleProxyTest {

    public static void main(String[] args) {
        HttpProxyServer server =
                DefaultHttpProxyServer.bootstrap()
                        .withPort(8080)
                        .start();
    }
}
