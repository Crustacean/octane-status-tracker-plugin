package io.jenkins.plugins.octanesuitegatebyembiti.security;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import jenkins.test.https.KeyStoreManager;

/** Real TLS fixture: Surefire trusts only the test certificate, without disabling verification. */
public final class OctaneTestHttpsServer {
  private OctaneTestHttpsServer() {}

  public static HttpsServer create() throws Exception {
    KeyStoreManager keys =
        new KeyStoreManager(
            Path.of(OctaneTestHttpsServer.class.getResource("/octane-test-tls.p12").toURI()),
            "test-only",
            "PKCS12");
    HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(keys.buildServerSSLContext()));
    return server;
  }
}
