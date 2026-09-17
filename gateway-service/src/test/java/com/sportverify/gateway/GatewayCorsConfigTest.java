package com.sportverify.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gateway CORS 配置断言测试（add-gateway-browser-cors）。
 * 验证默认允许本地 5173 来源；未配置来源不使用 *（凭证场景安全）。
 * 使用资源文件字符串断言，避免全量 @SpringBootTest 拉起 Nacos 等依赖。
 */
class GatewayCorsConfigTest {

    @Test
    void local5173OriginsAllowedAndNoWildcard() throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("application.yml");
        assertNotNull(url, "application.yml should be on classpath for test");

        String content = Files.readString(Paths.get(url.toURI()));

        assertTrue(content.contains("http://127.0.0.1:5173"), "默认允许 127.0.0.1:5173");
        assertTrue(content.contains("http://localhost:5173"), "默认允许 localhost:5173");

        int start = content.indexOf("globalcors:");
        assertTrue(start >= 0, "globalcors 配置存在");

        String corsPart = content.substring(start, Math.min(start + 600, content.length()));
        // 只检查 allowedOrigins 段落不含 *
        int oStart = corsPart.indexOf("allowedOrigins:");
        int oEnd = corsPart.indexOf("allowedMethods:");
        String originsSection = (oStart >= 0 && oEnd > oStart) ? corsPart.substring(oStart, oEnd) : corsPart;
        assertFalse(originsSection.contains("- \"*\""), "凭证场景禁止 * 来源");
        assertTrue(corsPart.contains("OPTIONS"), "允许 OPTIONS 预检");
        assertTrue(corsPart.contains("allowCredentials: true"), "允许凭证");
        assertTrue(corsPart.contains("allowedHeaders"), "有 allowedHeaders");
    }
}