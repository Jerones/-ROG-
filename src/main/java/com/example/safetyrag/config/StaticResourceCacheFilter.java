package com.example.safetyrag.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 给前端静态资源（index.html / app.js / style.css）加上协商缓存头。
 *
 * <p>不设这个头时，Spring Boot 只发 Last-Modified，浏览器会按「启发式规则」自行估算一个缓存有效期，
 * 在这段时间里直接拿本地副本、连服务器都不问。结果就是：后端已经换成了新的 app.js，
 * 用户刷新页面看到的仍是旧版界面（例如文档列表里看不到「查看」按钮）。
 *
 * <p>这里改成 {@code no-cache}：浏览器仍然缓存文件，但每次使用前必须带
 * If-None-Match / If-Modified-Since 回来问一句；文件没变服务器回 304（几乎零开销），
 * 文件变了就拿到新内容。既保证改动立刻生效，也不会重复传文件。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StaticResourceCacheFilter extends OncePerRequestFilter {

    private static final String CACHE_CONTROL_VALUE = "no-cache, must-revalidate";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isStaticAsset(request.getRequestURI())) {
            // 用 setHeader 而非 addHeader，避免上游已经写过同名头造成重复
            response.setHeader("Cache-Control", CACHE_CONTROL_VALUE);
            // 告诉中间的代理/网关不要把这个响应当作可长期复用的副本
            response.setHeader("Pragma", "no-cache");
        }
        chain.doFilter(request, response);
    }

    private boolean isStaticAsset(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.endsWith(".js")
                || uri.endsWith(".css")
                || uri.endsWith(".html")
                || uri.equals("/");
    }
}
