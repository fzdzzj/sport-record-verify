package com.sportverify.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
