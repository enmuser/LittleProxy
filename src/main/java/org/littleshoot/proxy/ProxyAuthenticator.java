package org.littleshoot.proxy;

/**
 * Interface for objects that can authenticate someone for using our Proxy on
 * the basis of a username and password.
 */
public interface ProxyAuthenticator {
    /**
     * Authenticates the user using the specified userName and password.
     * 
     * @param userName
     *            The user name.
     * @param password
     *            The password.
     * @return <code>true</code> if the credentials are acceptable, otherwise
     *         <code>false</code>.
     */
    boolean authenticate(String userName, String password);

  /**
   * The realm value to be used in the request for proxy authentication ("Proxy-Authenticate"
   * header). Returning null will cause the string "Restricted Files" to be used by default.
   *
   * <p>*在代理身份验证请求中使用的领域值（“ Proxy-Authenticate”标头）。
   * 返回null将导致字符串默认情况下使用“受限制的文件”。
   *
   * @return
   */
  String getRealm();
}
