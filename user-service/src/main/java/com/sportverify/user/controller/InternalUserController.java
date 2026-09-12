package com.sportverify.user.controller;

import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.common.result.Result;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * user-api Feign 契约实现（用户概要域，接口与实现分离，规范「服务间 Feign 契约」）。
 *
 * <p>消费方为 record-service（榜单昵称补齐）：总榜前 N 名按 userId 集合
 * <b>一次批量</b>拉取昵称，避免逐条远程调用放大延迟（审批版 §4.5 榜单约束）。</p>
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserMapper userMapper;

    /**
     * 按用户ID集合批量查概要（榜单场景）：
     * selectBatchIds 单次回表；不存在的用户不出现在结果中（消费方以「用户{id}」兜底展示）。
     * 手机号脱敏后下发（UserDTO 契约），不带密码哈希。
     */
    @GetMapping("/users")
    public Result<List<UserDTO>> listByIds(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.success(List.of());
        }
        List<UserDTO> users = userMapper.selectBatchIds(ids).stream()
                .map(this::toDto)
                .toList();
        return Result.success(users);
    }

    /** 实体 → 契约 DTO（脱敏手机号；昵称可空由消费方兜底） */
    private UserDTO toDto(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setPhone(maskPhone(user.getPhone()));
        dto.setNickname(user.getNickname());
        dto.setStatus(user.getStatus());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }

    /** 手机号脱敏：保留前 3 后 4（138****1234），长度不足直接不回传 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return null;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
