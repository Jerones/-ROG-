package com.example.safetyrag.config;

import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 把访问大模型的 HTTP 传输层统一到 JDK 自带的 {@link java.net.http.HttpClient}。
 *
 * <p>背景（实测数据，同一台机器、同一个 key、同一个请求体）：
 * <ul>
 *   <li>Apache HttpClient5 在应用里发 chat 请求，稳定在 ~19 秒后 "Connection reset"，连续 4 次全失败；
 *       但同样的代码在独立 JVM 里 1.5 秒就成功，embedding 走它也是 0.17 秒成功；</li>
 *   <li>reactor-netty（流式用的 WebClient 默认传输层）同样是 ~20 秒 reset；</li>
 *   <li>JDK HttpClient：非流式 3/3 成功，单次 4~6 秒。</li>
 * </ul>
 * 也就是说本机到百炼的网络路径对这两种客户端的连接处理不友好，而 JDK 的没问题。
 * 非流式链路已由 application.yml 的 {@code spring.http.client.factory: jdk} 解决，
 * 这里补流式链路：Spring AI 的 stream() 用的是 Spring Boot 自动配置的 WebClient.Builder，
 * 通过 WebClientCustomizer 把 connector 换成 JDK 实现即可，业务代码不用改。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public WebClientCustomizer jdkHttpClientWebClientCustomizer() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpConnector connector = new JdkClientHttpConnector(httpClient);
        // 读超时按"单次读"算，流式逐 token 到达不受影响；只防上游彻底挂死不返回
        connector.setReadTimeout(Duration.ofSeconds(120));
        return builder -> builder.clientConnector(connector);
    }
}
