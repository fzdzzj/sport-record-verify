package com.sportverify.record.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.common.PageResult;
import com.sportverify.api.record.dto.MyStatsDTO;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.RecordSplitsDTO;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.RecordSummaryDTO;
import com.sportverify.api.record.dto.FeedItemDTO;
import com.sportverify.api.record.dto.SportRecordDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.record.dto.WeeklyDigestDTO;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.SportType;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.service.SportRecordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记录域对外接口（网关 /record/** → StripPrefix → 本控制器）。
 *
 * <p>身份认定（add-jwt-auth，见 ADR-0007）：<b>已从网关注入的 X-User-Id 读取</b>——
 * 提交/申诉的 userId 不再信任请求体显式携带值；{@code app.auth.enabled=true} 时以
 * X-User-Id 为准（显式值不一致 → 403 越权），false（默认）时降级旧行为（显式携带）。
 * 外部访问路径形如 {@code /record/api/records...}（与骨架网关约定一致）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordController {

    private final SportRecordService sportRecordService;

    /** 鉴权降级开关（false=旧行为显式携带 userId；true=以网关注入的 X-User-Id 为准） */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 网关注入的 userId 请求头（唯一可信来源，外部伪造同名头被网关覆盖，见 ADR-0007） */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 提交运动记录（规范「轨迹提交幂等」）。
     * 重复提交返回 3004 并携带原结果（记录ID + 当前状态）。
     */
    @PostMapping
    public Result<RecordSubmitResultDTO> submit(@Valid @RequestBody RecordSubmitDTO dto,
                                                HttpServletRequest request) {
        // 身份认定：true 时以 X-User-Id 覆盖 dto.userId（越权不一致 → 403）
        dto.setUserId(resolveUserId(request, dto.getUserId()));
        RecordSubmitResultDTO result = sportRecordService.submit(dto);
        if (result.isDuplicated()) {
            return new Result<>(ResultCode.IDEMPOTENT_CONFLICT.getCode(), "重复提交，返回原结果", result);
        }
        return Result.success(result);
    }

    /**
     * 查询判定结果（verify_db，经 verify-api 拉取；未终判时 verdict=VERIFYING）。
     *
     * <p>读取面授权（TASK-221）：caller 经服务层 {@code assertCanView} 门判断——true 时以
     * 网关 X-User-Id 为读取者（缺失 → 1001），false 时 caller=null（降级态放行，行为与今日
     * 逐字节一致）。关闭了此前「任何人可读任意判定」的开放暴露。</p>
     */
    @GetMapping("/{id}/verify-result")
    public Result<VerificationResultDTO> verifyResult(@PathVariable("id") Long id,
                                                      HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(sportRecordService.getVerifyResult(id, caller));
    }

    /**
     * 我的记录分页查询（TASK-211 个人统计面板数据面，归属即身份）。
     *
     * <p>身份语义（与 points/appeal 的 resolveUserId 口径一致，见 ADR-0007）：
     * {@code app.auth.enabled=true} 时从网关注入的 {@code X-User-Id} 读取归属人，
     * 缺失或空白 → 1001（HTTP 401）；false 时降级为显式 {@code userId} 查询参数，
     * 必填缺失 → 400 PARAM。记录严格按解析出的归属人过滤
     * （{@code WHERE user_id=?}），过滤即身份，构造上无越权。</p>
     * <p>可选过滤（TASK-224）：sportType（运动类型枚举域校验）、status（校验状态域校验）、
     * startDate/endDate（开始日期范围过滤，start<=end 校验）。非法参数抛 400（IllegalArgumentException）。
     * 全部缺省时不过滤，保持向后兼容。</p>
     *
     * <p>分页：{@code page>=1}，{@code size} 默认 20、上限 500；响应形态为 api 契约
     * {@link PageResult}（TASK-262 R3 由 MP {@code Page} 薄转换，跨服务分页统一口径）。首页统计卡与近 30 天里程自 TASK-231 起改由
     * {@code /my/stats} 全量聚合承担，本端点的 500 上限不再影响统计口径。</p>
     */
    @GetMapping("/my")
    public Result<PageResult<RecordSummaryDTO>> myRecords(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(value = "sportType", required = false) Integer sportType,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletRequest request) {
        Long owner = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && owner == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        validateFilterParams(sportType, status, startDate, endDate);
        return Result.success(toPageResult(
                sportRecordService.pageMyRecords(owner, page, size, sportType, status, startDate, endDate)));
    }

    /**
     * 好友动态 feed（TASK-222 add-friend-feed，跨服务聚合读路径）。
     *
     * <p>身份语义（与 /my 同构，见 ADR-0007）：{@code app.auth.enabled=true} 时以网关注入的
     * {@code X-User-Id} 为读取者 caller，缺失或空白 → 1001（HTTP 401）；false 时 caller=null
     * （降级态）→ PUBLIC(0) 全站时间线（不区分归属，TASK-204 零扰动先例）。feed 的过滤即身份
     * 在服务层 {@code pageFeed}：auth on 时仅返回本人（任意可见性）+ 好友（PUBLIC/FRIENDS）记录，
     * 陌生人记录不入 feed——预过滤与 {@code assertCanView} 在 feed 场景下等价（见判别式 R6）。</p>
     */
    @GetMapping("/feed")
    public Result<PageResult<FeedItemDTO>> feed(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(toPageResult(sportRecordService.pageFeed(caller, page, size)));
    }

    /**
     * 我的连续打卡统计（TASK-220 个人仪表盘数据面，归属即身份）。
     *
     * <p>身份语义与 {@code /my} 完全一致（见 myRecords javadoc）：true 时从网关注入的
     * {@code X-User-Id} 读取归属人，缺失或空白 → 1001（HTTP 401）；false 时降级为显式
     * {@code userId} 查询参数，必填缺失 → 400 PARAM。统计口径见
     * {@link com.sportverify.api.record.dto.MyStreakDTO}：仅计入通过终态
     * （PASSED / RE_PASSED），同一天多条只算一天，今日无记录从昨日续算。</p>
     */
    @GetMapping("/my/streak")
    public Result<MyStreakDTO> myStreak(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        Long owner = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && owner == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        return Result.success(sportRecordService.myStreak(owner));
    }

    /**
     * 个人核心统计（TASK-231 服务端聚合端点，归属即身份；与 /my、/my/streak 同构）。
     *
     * <p>身份语义与 {@code /my} 完全一致（见 myRecords javadoc）：true 时从网关注入的
     * {@code X-User-Id} 读取归属人，缺失或空白 → 1001（HTTP 401）；false 时降级为显式
     * {@code userId} 查询参数，必填缺失 → 400 PARAM。记录严格按解析出的归属人过滤
     * （{@code WHERE user_id=?}），过滤即身份，构造上无越权。</p>
     *
     * <p>与 {@code /my} 的分页数据面解耦：本端点不接受 page/size/过滤参数，恒基于该用户
     * 全量记录做服务端聚合，翻页不再污染统计口径。返回口径见
     * {@link com.sportverify.api.record.dto.MyStatsDTO}。</p>
     */
    @GetMapping("/my/stats")
    public Result<MyStatsDTO> myStats(
            @RequestParam(value = "userId", required = false) Long userId,
            HttpServletRequest request) {
        Long owner = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && owner == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        return Result.success(sportRecordService.myStats(owner));
    }

    /**
     * 记录详情基础指标（TASK-245 B22 add-record-detail-metrics）：返回单条记录的
     * 运动类型/距离/时长/起止时间/状态/可见性，供前端详情页渲染指标卡片与可见性下拉。
     *
     * <p>归属校验（与 deleteRecord 同款，见 ADR-0007 / R1）：{@code app.auth.enabled=true} 时以网关注入的
     * {@code X-User-Id} 为调用者，服务层断言调用者==记录归属人（非本人 → 403/1002 权限错误态）；
     * false 时 caller=null 降级旧行为。读取面不对 FRIENDS/PUBLIC 陌生人开放（详情卡片供本人使用）。</p>
     */
    @GetMapping("/{id}")
    public Result<SportRecordDTO> recordDetail(@PathVariable("id") Long id,
                                               HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(sportRecordService.getRecordDetail(id, caller));
    }

    /**
     * 原地修改记录可见性（TASK-245 B22/B29，PATCH /api/records/{id}/visibility）。
     *
     * <p>入参 {@code visibility} 经 {@code @RequestParam} 接收，仅限 0(PUBLIC)/1(FRIENDS)/2(PRIVATE)，
     * 非法值由服务层抛 IllegalArgumentException → 全局 400(BAD_REQUEST)。仅本人可改：
     * auth enabled=true 时以网关注入 X-User-Id 为调用者，非本人 → 403/1002。</p>
     */
    @PatchMapping("/{id}/visibility")
    public Result<SportRecordDTO> updateVisibility(@PathVariable("id") Long id,
                                                   @RequestParam("visibility") Integer visibility,
                                                   HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(sportRecordService.updateVisibility(caller, id, visibility));
    }

    /**
     * 提交申诉（仅 REJECTED 记录可申诉，规范「申诉提交」场景）。
     * 归属校验（TASK-204）：true 时以网关 X-User-Id 为调用者，服务层断言调用者==记录归属人；
     * false 时 caller=null 降级旧行为。
     */
    @PostMapping("/{id}/appeal")
    public Result<AppealDTO> appeal(@PathVariable("id") Long id,
                                    @Valid @RequestBody AppealCreateDTO dto,
                                    HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, dto.getUserId()) : null;
        return Result.success(sportRecordService.appeal(id, caller, dto.getReason()));
    }

    /**
     * 申诉逆向全链路透明追踪（TASK-245 B26，GET /api/records/{id}/appeal）：返回该记录关联的
     * 申诉进度（存在性 + 处理状态），供 verdict 页渲染 <a-steps> 进度条并禁用重复提交（防 3005）。
     *
     * <p>归属校验与 POST /appeal 同款：auth enabled=true 时以网关注入 X-User-Id 为调用者，
     * 服务层断言调用者==记录归属人（非本人 → 403）。申诉存在性/进度由记录状态推导
     * （APPEALING=待复核、RE_PASSED=改判通过、RE_CONFIRMED=维持拒绝），无申诉时 status=null。</p>
     */
    @GetMapping("/{id}/appeal")
    public Result<AppealDTO> appealDetail(@PathVariable("id") Long id,
                                          HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(sportRecordService.getAppeal(id, caller));
    }

    /**
     * 轨迹分页查询（分片分页：验证 MyBatis-Plus 分页插件 × ShardingSphere 代理数据源）。
     * 归属校验（TASK-204）：true 时以网关 X-User-Id 为调用者，服务层断言调用者==记录归属人；
     * false 时 caller=null 降级旧行为。
     */
    @GetMapping("/{id}/points")
    public Result<PageResult<TrackPointDTO>> points(@PathVariable("id") Long id,
                                                    @RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(toPageResult(sportRecordService.pagePoints(id, caller, page, size)));
    }

    /**
     * 公里分段配速切片（TASK-273，GET /api/records/{id}/splits）：全程 Haversine 累计路程跨
     * 整公里阈值线性插值越线时刻逐公里切片，返回逐段耗时/配速/均速、最快最慢公里与余程段。
     *
     * <p>归属校验（与 /{id} 详情同款，ADR-0007）：auth enabled=true 时以网关注入 X-User-Id
     * 为调用者，服务层断言调用者==记录归属人（非本人 → 403/1002）；false 时 caller=null 降级旧行为。</p>
     */
    @GetMapping("/{id}/splits")
    public Result<RecordSplitsDTO> splits(@PathVariable("id") Long id,
                                          HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : null;
        return Result.success(sportRecordService.getRecordSplits(caller, id));
    }

    /**
     * 删除运动记录（TASK-223，硬删除级联恰四表 + Redis 缓存清理）。
     *
     * <p>身份语义与归属门（R1，与 /my 同构）：
     * {@code app.auth.enabled=true} 时从网关注入的 {@code X-User-Id} 读取调用者
     * （缺失或空白 → 1001 / HTTP 401）；false 时降级为显式 {@code userId} 查询参数
     * （缺失 → 400 PARAM）。服务层严格断言调用者为记录归属人，非本人删除 → 1002 / HTTP 403。</p>
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteRecord(@PathVariable("id") Long id,
                                     @RequestParam(value = "userId", required = false) Long userId,
                                     HttpServletRequest request) {
        Long caller = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && caller == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        sportRecordService.deleteRecord(caller, id);
        return Result.success();
    }

    /**
     * 每周运动智能周报（TASK-274，GET /api/records/weekly-digest）：自然周（周一至周日）
     * 通过终态记录聚合总里程/耗时/热量/出勤天数/主修类型/最快配速，叠加环比前一周
     * 增减率与个性化恢复建议（网关转发路径 /record/api/records/weekly-digest）。
     *
     * <p>身份语义与 {@code /my} 完全一致（见 myRecords javadoc）：true 时从网关注入的
     * {@code X-User-Id} 读取归属人，缺失或空白 → 1001（HTTP 401）；false 时降级为显式
     * {@code userId} 查询参数，必填缺失 → 400 PARAM。服务层再断言 caller==userId
     * （非本人 → 403/1002，双层防护）。可选 {@code weekStart}（ISO 日期，服务层归一化
     * 到所在周周一；缺省取上一完整自然周）。</p>
     */
    @GetMapping("/weekly-digest")
    public Result<WeeklyDigestDTO> weeklyDigest(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "weekStart", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
            HttpServletRequest request) {
        Long owner = authEnabled ? resolveUserId(request, null) : userId;
        if (!authEnabled && owner == null) {
            throw new IllegalArgumentException("缺少必须的 userId 查询参数");
        }
        return Result.success(sportRecordService.getWeeklyDigest(owner, owner, weekStart));
    }

    /**
     * 校验我的记录过滤参数（TASK-224）：sportType 枚举外、status 状态域外、start>end 抛 400（IllegalArgumentException）。
     */
    private void validateFilterParams(Integer sportType, Integer status, LocalDate startDate, LocalDate endDate) {
        if (sportType != null && SportType.fromCode(sportType) == null) {
            throw new IllegalArgumentException("非法运动类型：" + sportType);
        }
        if (status != null && !isValidStatus(status)) {
            throw new IllegalArgumentException("非法记录状态：" + status);
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
    }

    private static boolean isValidStatus(Integer status) {
        if (status == null) {
            return false;
        }
        for (RecordStatus s : RecordStatus.values()) {
            if (s.getCode() == status) {
                return true;
            }
        }
        return false;
    }

    /**
     * 身份认定（数据隔离核心，见 ADR-0007）：true 时以网关 X-User-Id 为准，
     * 显式携带值不一致 → 403（1002，越权）；false 时降级为显式携带值（旧行为）。
     */
    private Long resolveUserId(HttpServletRequest request, Long claimed) {
        if (authEnabled) {
            String header = request.getHeader(HEADER_USER_ID);
            if (header == null || header.isBlank()) {
                throw new BizException(ResultCode.UNAUTHORIZED, "缺少网关注入的 X-User-Id");
            }
            Long tokenUserId = Long.parseLong(header);
            if (claimed != null && !claimed.equals(tokenUserId)) {
                // 调用方试图以他人身份提交/申诉：不信任自报家门，越权 → 403
                log.warn("越权访问被拒：tokenUserId={}, claimed={}", tokenUserId, claimed);
                throw new BizException(ResultCode.FORBIDDEN);
            }
            return tokenUserId;
        }
        return claimed;
    }

    /**
     * MP {@code Page} → api 契约 {@link PageResult} 薄转换（TASK-262 R3/G14）：
     * 字段语义与 MP Page 对齐（current/size/total/records），跨服务分页统一走 api 类型，
     * 不再外抛 MP {@code Page}（序列化无 {@code pages} 等实现细节字段）。
     */
    private static <T> PageResult<T> toPageResult(Page<T> page) {
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }
}
