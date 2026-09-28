package org.example.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * 程序化放宽 Tomcat 请求头大小限制。
 * 聊天消息已改经 POST body 传递，请求头不再承载长文本；此处保留 32KB 上限作为防御性设置，
 * 兼容个别携带较长 Header（如 Authorization/跟踪 ID）的请求，避免 400 拒绝。
 */
@Component
public class WebServerConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    /** 请求头大小上限：32KB */
    private static final int MAX_HTTP_HEADER_SIZE = 32 * 1024;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector ->
                connector.setProperty("maxHttpHeaderSize", String.valueOf(MAX_HTTP_HEADER_SIZE)));
    }
}
