package com.sportverify.verify.controller;

import com.sportverify.common.result.Result;
import com.sportverify.verify.dto.RuleVersionCreateRequest;
import com.sportverify.verify.dto.RuleVersionGrayRequest;
import com.sportverify.verify.entity.RuleVersion;
import com.sportverify.verify.service.RuleVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则版本管理接口（管理端，Swagger 级；规范「版本管理接口」，审批版 §7.4）。
 *
 * <p>本控制器属治理面（add-admin-rbac，见 ADR-0007）：外部路径 {@code /verify/rules/**}
 * 已从网关白名单移除并纳入角色校验，仅 <b>ADMIN 角色</b> token 可访问（普通用户 403/1002、
 * 未登录 401/1001）；鉴权只依赖网关过滤（本服务不自行校验 token，角色由网关注入 X-Role）。
 * 三端点覆盖灰度发布全流程：创建（快照落库 + GRAY）→ 调灰度（0 = 秒级回滚）
 * → 全量（100 + ACTIVE + 旧版本退役），返回体均携带最新版本信息与灰度比例。</p>
 */
@RestController
@RequestMapping("/rules")
@RequiredArgsConstructor
public class RuleVersionController {

    private final RuleVersionService ruleVersionService;

    /** 创建规则版本：快照 rules_json 落库，状态 GRAY（可带初始灰度比例直接开始采样） */
    @PostMapping("/versions")
    public Result<RuleVersion> create(@RequestBody RuleVersionCreateRequest request) {
        return Result.success(ruleVersionService.createVersion(request));
    }

    /** 调整灰度比例 0-100：置 0 即秒级回滚（新版本不再被采样，基线不受影响） */
    @PatchMapping("/versions/{id}/gray")
    public Result<RuleVersion> gray(@PathVariable("id") Long id,
                                    @RequestBody RuleVersionGrayRequest request) {
        return Result.success(ruleVersionService.updateGrayRatio(id, request.getGrayRatio()));
    }

    /** 全量发布：gray_ratio=100 + 状态 ACTIVE，旧版本 RETIRED（此后本版本即基线） */
    @PostMapping("/versions/{id}/activate")
    public Result<RuleVersion> activate(@PathVariable("id") Long id) {
        return Result.success(ruleVersionService.activate(id));
    }
}
