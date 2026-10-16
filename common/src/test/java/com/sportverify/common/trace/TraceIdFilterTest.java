package com.sportverify.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TraceIds / TraceIdFilter 单测（纯 Mock 请求对象，无容器）。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        TraceIds.clear();
    }

    @Test
    void resolveOrCreate_blank_generates() {
        String a = TraceIds.resolveOrCreate(null);
        String b = TraceIds.resolveOrCreate("  ");
        assertNotNull(a);
        assertFalse(a.isBlank());
        assertNotNull(b);
        assertFalse(b.isBlank());
    }

    @Test
    void resolveOrCreate_keepsIncoming() {
        assertEquals("client-1", TraceIds.resolveOrCreate(" client-1 "));
    }

    /** E28（TASK-256）：合法既有形态沿用（32 位十六进制） */
    @Test
    void resolveOrCreate_validLengthAndChars_reused() {
        String hex32 = "0123456789abcdef0123456789abcdef";
        assertEquals(hex32, TraceIds.resolveOrCreate(hex32));
    }

    /** E28：边界——64 字符（上限）与 1 字符（下限）合规沿用 */
    @Test
    void resolveOrCreate_boundary64And1_reused() {
        String len64 = "a".repeat(64);
        assertEquals(len64, TraceIds.resolveOrCreate(len64));
        assertEquals("a", TraceIds.resolveOrCreate("a"));
        String len64Mixed = "Ab-1_z".repeat(10) + "Wxyz"; // 60+4=64，含字母/数字/下划线/连字符
        assertEquals(64, len64Mixed.length());
        assertEquals(len64Mixed, TraceIds.resolveOrCreate(len64Mixed));
    }

    /** E28：超长（65 字符）入站 ID 重新生成（不沿用，防放大日志） */
    @Test
    void resolveOrCreate_tooLong_regenerates() {
        String tooLong = "x".repeat(65);
        String out = TraceIds.resolveOrCreate(tooLong);
        assertFalse(tooLong.equals(out), "65 字符超长 ID 应重新生成");
        assertFalse(out.isBlank());
    }

    /** E28：非法字符（空格/中文/控制字符）重新生成（防挂他人链路/日志注入） */
    @Test
    void resolveOrCreate_invalidChars_regenerate() {
        assertNotEquals("abc def", TraceIds.resolveOrCreate("abc def"), "含空格非法，应重新生成");
        assertNotEquals("中文id", TraceIds.resolveOrCreate("中文id"), "含中文非法，应重新生成");
        assertNotEquals("a\nb", TraceIds.resolveOrCreate("a\nb"), "含控制字符非法，应重新生成");
    }

    @Test
    void filter_incomingHeader_putsMdcAndEchoesResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/record/api/x");
        request.addHeader(TraceIds.HEADER, "fixed-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertEquals("fixed-trace", TraceIds.current());
            assertEquals("fixed-trace", ((jakarta.servlet.http.HttpServletResponse) res).getHeader(TraceIds.HEADER));
        });

        assertEquals("fixed-trace", response.getHeader(TraceIds.HEADER));
        assertNull(TraceIds.current());
    }

    @Test
    void filter_missingHeader_generatesAndClears() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] seen = new String[1];

        filter.doFilter(request, response, (req, res) -> seen[0] = TraceIds.current());

        assertNotNull(seen[0]);
        assertTrue(seen[0].length() >= 16);
        assertEquals(seen[0], response.getHeader(TraceIds.HEADER));
        assertNull(TraceIds.current());
    }
}
