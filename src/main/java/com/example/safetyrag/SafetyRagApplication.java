package com.example.safetyrag;

import com.example.safetyrag.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 基于 RAG 的施工安全规范智能问答系统 —— 启动类。
 *
 * <p>在 IntelliJ IDEA 中直接运行本类的 main 方法即可启动，
 * 浏览器访问 http://localhost:8080 打开双菜单前端页面。</p>
 *
 * <p>启动前需要有一个可用的 Chroma 服务（向量库是本项目的必要依赖，不做本地兜底）：
 * 默认连 {@code http://localhost:8000}，换地址用环境变量 {@code CHROMA_HOST}，
 * 或启动参数 {@code --spring.ai.vectorstore.chroma.client.host=http://<ip>}。</p>
 *
 * <p>{@code app.demo-mode=true} 时不调用任何外部模型 API（本地哈希向量 +
 * 抽取式回答），无 API key 也能跑通全链路；默认值是 false，即走真实模型。
 * 注意演示模式只省掉模型调用，向量库仍然要连 Chroma。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class SafetyRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(SafetyRagApplication.class, args);
    }
}
