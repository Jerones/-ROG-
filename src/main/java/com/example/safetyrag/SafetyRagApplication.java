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
 * <p>两种运行形态（靠 profile 互斥切换，VectorStore 接口不变）：</p>
 * <ul>
 *   <li><b>默认（不加 profile）</b>：SimpleVectorStore，向量存内存并落盘到
 *       {@code data/simple-vector-store.json}，零外部依赖，装完就能跑；</li>
 *   <li><b>chroma profile</b>：VectorStore 换成 Chroma，
 *       启动参数加 {@code --spring.profiles.active=chroma}（需先起 Chroma 服务）。</li>
 * </ul>
 * <p>{@code app.demo-mode=true} 时不调用任何外部模型 API（本地哈希向量 +
 * 抽取式回答），无 API key 也能演示全链路；默认值是 false，即走真实模型。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class SafetyRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(SafetyRagApplication.class, args);
    }
}
