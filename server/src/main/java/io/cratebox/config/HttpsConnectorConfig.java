package io.cratebox.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * 폰 카메라 스캔용 HTTPS 추가 커넥터 (기본 8443). http 8087은 그대로 두고 함께 연다.
 * 브라우저 카메라(getUserMedia)는 보안 컨텍스트(https/localhost)에서만 열리므로,
 * 현장판매 화면을 폰에서 쓰려면 https://<서버IP>:8443 으로 접속해야 한다.
 * 인증서는 개발·사내망용 자체서명 — 폰 브라우저에서 경고를 한 번 승인하고 사용한다.
 */
@Configuration
@ConditionalOnProperty(name = "cratebox.https.enabled", havingValue = "true")
public class HttpsConnectorConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpsConnectorConfig.class);

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpsConnector(
            @Value("${cratebox.https.port:8443}") int port) {
        return factory -> {
            ClassPathResource keystore = new ClassPathResource("tls/cratebox-dev.p12");
            if (!keystore.exists()) {
                log.warn("tls/cratebox-dev.p12 없음 — HTTPS 커넥터를 건너뜁니다");
                return;
            }
            try {
                // Tomcat은 keystore를 파일 경로로 요구한다 (jar 내부 접근 불가 → 임시 파일로 추출)
                Path file = Files.createTempFile("cratebox-tls", ".p12");
                try (InputStream in = keystore.getInputStream()) {
                    Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                }
                file.toFile().deleteOnExit();
                factory.addAdditionalTomcatConnectors(sslConnector(port, file));
                log.info("HTTPS 커넥터 활성화: https://<host>:{} (자체서명, 폰 카메라 스캔용)", port);
            } catch (IOException e) {
                log.warn("HTTPS 커넥터 초기화 실패 — http만 사용합니다", e);
            }
        };
    }

    private Connector sslConnector(int port, Path keystoreFile) {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setPort(port);
        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);
        SSLHostConfig ssl = new SSLHostConfig();
        SSLHostConfigCertificate cert = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.RSA);
        cert.setCertificateKeystoreFile(keystoreFile.toAbsolutePath().toString());
        cert.setCertificateKeystorePassword("cratebox-dev");
        cert.setCertificateKeyAlias("cratebox");
        ssl.addCertificate(cert);
        protocol.addSslHostConfig(ssl);
        return connector;
    }
}
