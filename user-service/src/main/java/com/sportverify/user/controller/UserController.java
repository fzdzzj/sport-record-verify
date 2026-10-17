package com.sportverify.user.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户资料域对外接口（网关 /user/api/user/** → StripPrefix → 本控制器，
 * TASK-245 B25 用户资料维护：查看与修改本人昵称）。
 *
 * <p>身份认定（add-jwt-auth，见 ADR-0007）：{@code app.auth.enabled=true} 时以网关注入的
 * {@code X-User-Id} 为唯一可信身份（请求体/参数不携带 userId，杜绝伪造他人资料）；
 * false（默认）时无可信自我服务身份来源，按 {@link AuthController#changePassword} 同款口径拒绝（401）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;

    /** 鉴权降级开关（true=以网关注入的 X-User-Id 为准；false=无可信身份来源，拒绝对自我资料操作） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头（唯一可信来源，外部伪造同名头被网关覆盖，见 ADR-0007） */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 查看个人资料（TASK-245 B25）：返回当前登录用户的 userId 与昵称等概要。
     *
     * <p>身份仅从网关注入 {@code X-User-Id} 解析（auth.on 时缺失/非法 → 401）；供
     * settings 页「个人资料」卡片展示。</p>
     */
    @GetMapping("/profile")
    public Result<UserDTO> profile(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        UserDTO dto = new UserDTO();
        dto.setId(userId);
        com.sportverify.user.entity.User user = userMapper.selectById(userId);
        if (user != null) {
            dto.setPhone(user.getPhone());
            dto.setNickname(user.getNickname());
            dto.setStatus(user.getStatus());
            dto.setCreatedAt(user.getCreatedAt());
        }
        return Result.success(dto);
    }

    /**
     * 修改个人昵称（TASK-245 B25，PUT /api/user/profile）：入参 {@code {"nickname": string}}。
     *
     * <p>昵称非空且 ≤32 字符（非法 → 400 由服务层抛 IllegalArgumentException）。
     * 仅可改本人：身份自网关注入 {@code X-User-Id}；更新 user 表 nickname 后返回更新后的概要。</p>
     */
    @PutMapping("/profile")
    public Result<UserDTO> updateProfile(@RequestBody Map<String, String> body,
                                         HttpServletRequest request) {
        Long userId = resolveUserId(request);
        String nickname = body == null ? null : body.get("nickname");
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("昵称不能为空");
        }
        nickname = nickname.trim();
        if (nickname.length() > 32) {
            throw new IllegalArgumentException("昵称长度不能超过 32 字符");
        }
        com.sportverify.user.entity.User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        // E30（TASK-256）：昵称单列 UPDATE，不整行回写 password_hash/role/status 等
        // （实体全字段非空触发整行 UPDATE 曾与改密/撤销管理员并发的毫秒级窗口可回写旧值，
        // 单 UPDATE 天然原子，无需 @Transactional/@Version）
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getNickname, nickname));

        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setNickname(nickname); // 局部变量携带新昵称（不整行回写）
        dto.setPhone(user.getPhone());
        dto.setStatus(user.getStatus());
        dto.setCreatedAt(user.getCreatedAt());
        return Result.success(dto);
    }

    /**
     * 自我服务身份认定（与 {@link com.sportverify.user.auth.controller.AuthController#changePassword} 同款）：
     * auth.enabled=true 时取网关注入的 X-User-Id，缺失/非法 → 401；false 时无可信身份来源，拒绝（401）。
     */
    private Long resolveUserId(HttpServletRequest request) {
        if (!authEnabled) {
            log.warn("资料操作被拒：鉴权未开启，无可信用户身份");
            throw new BizException(ResultCode.UNAUTHORIZED, "鉴权未开启，无法识别用户身份");
        }
        String header = request.getHeader(HEADER_USER_ID);
        if (header == null || header.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED, "缺少网关注入的 X-User-Id");
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.UNAUTHORIZED, "X-User-Id 格式非法");
        }
    }
}