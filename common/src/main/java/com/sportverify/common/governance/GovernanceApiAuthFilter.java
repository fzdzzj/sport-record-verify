package com.sportverify.common.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 网关治理面专用凭证过滤器（TASK-136 第二阶段；TASK-201 升级为 HMAC 签名凭证）。
 *
 * <p>保护服务本地治理端点：verify 的 /api/appeals/** 与 /rules/**，leaderboard 的 /api/leaderboard/daily。
 * 独立于 /internal/** 的 X-Internal-Token。
 * 凭证经 GovernanceCredentialCodec 验签（HMAC-SHA256，绑定时间戳与角色）并断言 role=ADMIN，全部不符 403。
 * 不以 X-Role 作为服务侧授权依据。
 * </p>
 *
 * <p>配置：app.governance.auth-enabled（默认 true）、app.governance.token（HMAC 密钥，生产必须注入，无演示默认）、
 * app.governance.max-skew-seconds（默认 300）。
 * strict 模式下缺密钥启动失败；非严格模式缺密钥也拒绝治理请求（不静默放行）。
 * </p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
@RequiredArgsConstructor
public class GovernanceApiAuthFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    /** 是否启用治理凭证校验 */
    @Value("${app.governance.auth-enabled:true}")
    private boolean authEnabled;

    /** 哨兵值：未显式配置时使用，任何情况下都不应视为可用密钥 */
    static final String UNSET_TOKEN = "__GOVERNANCE_TOKEN_UNSET__";

    /** HMAC 密钥（TASK-201：值语义从静态共享密钥升级为 HMAC 签名密钥）：生产必须经 GOVERNANCE_TOKEN 注入，无默认演示值 */
    @Value("${app.governance.token:${GOVERNANCE_TOKEN:" + UNSET_TOKEN + "}}")
    private String expectedToken;

    /** 凭证时间窗（秒）：验签通过且 |now-ts| <= 该值的凭证方放行 */
    @Value("${app.governance.max-skew-seconds:300}")
    private long maxSkewSeconds;

    /** 严格模式：缺密钥启动失败 */
    @Value("${app.security.strict:false}")
    private boolean strictMode;

    /** 受保护路径模式原文（逗号分隔，各服务在 yml 中配置自己的治理路径）。
     *  @Value 对 List<String> 不做逗号切分（生产上下文无 beanFactory ConversionService），须在 init 自行解析 */
    @Value("${app.governance.protected-paths:}")
    private String protectedPathsConfig;

    /** 解析后的受保护路径模式；单测可直填 */
    private List<String> protectedPaths = List.of();

    @PostConstruct
    void init() {
        protectedPaths = splitPatterns(protectedPathsConfig);
        // 仅当本服务实际配置了受保护路径时才 fail-fast，避免 user/record 等无治理面服务被误伤
        if (strictMode && hasProtectedPaths() && !isConfiguredToken(expectedToken)) {
            throw new IllegalStateException(
                    "app.security.strict=true 但 app.governance.token 未显式注入，拒绝启动");
        }
    }

    /** 逗号分隔 → 模式列表：去空白、丢空段 */
    private static List<String> splitPatterns(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private boolean hasProtectedPaths() {
        if (protectedPaths == null || protectedPaths.isEmpty()) {
            return false;
        }
        for (String path : protectedPaths) {
            if (path != null && !path.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConfiguredToken(String token) {
        // TASK-201：密钥可用性（非空、非哨兵、UTF-8 >= 32 字节）
        return GovernanceCredentialCodec.isUsableKey(token);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!authEnabled || !isProtectedPath(uri)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 受保护路径：必须有有效密钥并验签通过且角色为 ADMIN；未配置/弱密钥/验签失败一律 403，不静默放行
        if (!isConfiguredToken(expectedToken)) {
            log.warn("治理凭证密钥未配置或过弱，拒绝受保护路径：path={}", uri);
            writeForbidden(response);
            return;
        }
        String provided = request.getHeader(GovernanceApiHeaders.TOKEN);
        String role = GovernanceCredentialCodec.verify(
                expectedToken, provided, maxSkewSeconds, System.currentTimeMillis() / 1000);
        if (role != null && GovernanceApiHeaders.ROLE_ADMIN.equals(role)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("治理凭证验签失败或非 ADMIN 角色：path={}, hasHeader={}", uri, provided != null);
        writeForbidden(response);
    }

    private boolean isProtectedPath(String uri) {
        if (uri == null || protectedPaths == null || protectedPaths.isEmpty()) {
            return false;
        }
        for (String p : protectedPaths) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (p.endsWith("/**")) {
                String base = p.substring(0, p.length() - 3);
                if (uri.equals(base) || uri.startsWith(base + "/")) {
                    return true;
                }
            } else if (uri.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.failure(ResultCode.FORBIDDEN));
    }
}
