package com.sportverify.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.common.PageResult;
import com.sportverify.api.user.dto.ClubCreateDTO;
import com.sportverify.api.user.dto.ClubDTO;
import com.sportverify.api.user.dto.ClubMemberDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.entity.ClubMember;
import com.sportverify.user.entity.RunningClub;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.ClubMemberMapper;
import com.sportverify.user.mapper.RunningClubMapper;
import com.sportverify.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 跑团服务（TASK-275 跑团俱乐部与团队里程榜系统）。
 *
 * <p>核心设计：</p>
 * <ul>
 *   <li><b>一人一团</b>：club_member 唯一键 uk_user_club (user_id) 物理保障，创建/加入前
 *       先查成员存在性拦截，并发窗口以 DuplicateKeyException 捕获兜底（user_badge 同款）；</li>
 *   <li><b>原子计数</b>：member_count 经 {@code member_count = member_count ± 1} 单 UPDATE
 *       原子累加/递减，不读改写整行；</li>
 *   <li><b>退团规则</b>：团长且有其他成员 → 禁止直接退出（需先移交/解散）；仅剩团长一人 →
 *       删成员行并置 status=0 解散、计数清零；普通成员 → 删成员行并原子递减；</li>
 *   <li><b>团长移交</b>（TASK-276 R1）：在任团长把职位移交给同团其他成员，同事务双 role 更新
 *       （CAPTAIN→MEMBER / MEMBER→CAPTAIN），成员数与创建者不变；</li>
 *   <li><b>昵称补齐</b>：团长/成员昵称经 userMapper 批量回表（selectBatchIds 一次往返），
 *       用户缺失时昵称留空不阻断列表。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClubService {

    private final RunningClubMapper runningClubMapper;
    private final ClubMemberMapper clubMemberMapper;
    private final UserMapper userMapper;

    /** 跑团广场单页上限（防手滑超大一页，对齐 MAX_FRIEND_PAGE_SIZE 范式） */
    private static final int MAX_CLUB_PAGE_SIZE = 100;
    /** 跑团广场默认页大小 */
    private static final int DEFAULT_CLUB_PAGE_SIZE = 20;
    /** 跑团名称长度上限（与 running_club.name VARCHAR(64) 同口径） */
    private static final int CLUB_NAME_MAX_LENGTH = 64;

    // ==================== 创建跑团 ====================

    /**
     * 创建跑团（TASK-275）：校验 caller 未加入任何跑团（一人一团）且名称未被占用，
     * 插入 running_club（creator_id=caller，member_count=1）与 club_member（role=CAPTAIN）。
     *
     * <p>业务冲突复用既有错误码：已入团 → 1002 FORBIDDEN；重名 → 3004 IDEMPOTENT_CONFLICT
     * （common 错误码枚举不在本任务白名单内，不新增 CLUB_* 段，语义由 message 承载）。</p>
     */
    @Transactional
    public ClubDTO createClub(Long caller, ClubCreateDTO dto) {
        if (caller == null || dto == null || dto.getName() == null || dto.getName().isBlank()) {
            throw new IllegalArgumentException("caller 与跑团名称不能为空");
        }
        String name = dto.getName().trim();
        if (name.length() > CLUB_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("跑团名称长度不能超过 " + CLUB_NAME_MAX_LENGTH + " 字符");
        }
        if (findMembership(caller) != null) {
            throw new BizException(ResultCode.FORBIDDEN, "已加入跑团，每人仅可创建或加入一个跑团");
        }
        Long sameName = runningClubMapper.selectCount(new LambdaQueryWrapper<RunningClub>()
                .eq(RunningClub::getName, name));
        if (sameName != null && sameName > 0) {
            throw new BizException(ResultCode.IDEMPOTENT_CONFLICT, "跑团名称已被占用，换一个名称试试");
        }

        LocalDateTime now = LocalDateTime.now();
        RunningClub club = new RunningClub();
        club.setName(name);
        club.setDescription(dto.getDescription());
        club.setBadgeUrl(dto.getBadgeUrl());
        club.setCreatorId(caller);
        club.setMemberCount(1);
        club.setStatus(RunningClub.STATUS_ACTIVE);
        club.setCreatedAt(now);
        runningClubMapper.insert(club);

        ClubMember member = new ClubMember();
        member.setClubId(club.getId());
        member.setUserId(caller);
        member.setRole(ClubMember.ROLE_CAPTAIN);
        member.setJoinedAt(now);
        try {
            clubMemberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            // 并发窗口兜底：uk_user_club 撞键 = 已在其他跑团（创建/加入竞态）
            throw new BizException(ResultCode.FORBIDDEN, "已加入跑团，每人仅可创建或加入一个跑团");
        }
        log.info("创建跑团成功：clubId={}, name={}, creator={}", club.getId(), name, caller);
        return buildClubDTOs(List.of(club)).get(0);
    }

    // ==================== 跑团广场（分页查询） ====================

    /**
     * 跑团广场查询（TASK-275）：仅展示正常跑团（status=1），支持名称关键词模糊过滤；
     * 分页钳制 page≥1、size 1..100（默认 20），按成员数降序、同数按 id 升序稳定排列。
     */
    public PageResult<ClubDTO> listClubs(String keyword, Integer page, Integer size) {
        int pageNum = (page == null || page < 1) ? 1 : page;
        int pageSize = (size == null || size < 1) ? DEFAULT_CLUB_PAGE_SIZE : Math.min(size, MAX_CLUB_PAGE_SIZE);
        LambdaQueryWrapper<RunningClub> wrapper = new LambdaQueryWrapper<RunningClub>()
                .eq(RunningClub::getStatus, RunningClub.STATUS_ACTIVE)
                .orderByDesc(RunningClub::getMemberCount)
                .orderByAsc(RunningClub::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(RunningClub::getName, keyword.trim());
        }
        Page<RunningClub> result = runningClubMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return new PageResult<>(result.getCurrent(), result.getSize(), result.getTotal(),
                buildClubDTOs(result.getRecords()));
    }

    // ==================== 跑团详情与成员 ====================

    /**
     * 跑团详情（TASK-275）：不存在或已解散（status=0）→ 3001（404）。
     */
    public ClubDTO getClub(Long clubId) {
        RunningClub club = requireActiveClub(clubId, "跑团不存在或已解散");
        return buildClubDTOs(List.of(club)).get(0);
    }

    /**
     * 跑团成员列表（TASK-275）：按加入时间升序，批量回表补齐昵称（一次 selectBatchIds）。
     */
    public List<ClubMemberDTO> listMembers(Long clubId) {
        if (clubId == null) {
            throw new IllegalArgumentException("clubId 不能为空");
        }
        if (runningClubMapper.selectById(clubId) == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND, "跑团不存在");
        }
        List<ClubMember> members = clubMemberMapper.selectList(new LambdaQueryWrapper<ClubMember>()
                .eq(ClubMember::getClubId, clubId)
                .orderByAsc(ClubMember::getJoinedAt));
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = members.stream().map(ClubMember::getUserId).distinct().toList();
        Map<Long, String> nicknames = resolveNicknames(userIds);

        List<ClubMemberDTO> result = new ArrayList<>(members.size());
        for (ClubMember m : members) {
            ClubMemberDTO dto = new ClubMemberDTO();
            dto.setId(m.getId());
            dto.setClubId(m.getClubId());
            dto.setUserId(m.getUserId());
            dto.setNickname(nicknames.get(m.getUserId()));
            dto.setRole(m.getRole());
            dto.setJoinedAt(m.getJoinedAt());
            result.add(dto);
        }
        return result;
    }

    // ==================== 加入 / 退出 / 我的跑团 ====================

    /**
     * 加入跑团（TASK-275）：caller 未入任何团（一人一团）、跑团存在且 status=1；
     * 插入 club_member（role=MEMBER）后原子累加 member_count。
     */
    @Transactional
    public ClubDTO joinClub(Long caller, Long clubId) {
        if (caller == null || clubId == null) {
            throw new IllegalArgumentException("caller 与 clubId 不能为空");
        }
        if (findMembership(caller) != null) {
            throw new BizException(ResultCode.FORBIDDEN, "已加入跑团，每人仅可加入一个跑团");
        }
        RunningClub club = requireActiveClub(clubId, "跑团不存在或已解散");

        ClubMember member = new ClubMember();
        member.setClubId(clubId);
        member.setUserId(caller);
        member.setRole(ClubMember.ROLE_MEMBER);
        member.setJoinedAt(LocalDateTime.now());
        try {
            clubMemberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            // 并发窗口兜底：uk_user_club 撞键 = 已在其他跑团
            throw new BizException(ResultCode.FORBIDDEN, "已加入跑团，每人仅可加入一个跑团");
        }
        runningClubMapper.update(null, new LambdaUpdateWrapper<RunningClub>()
                .eq(RunningClub::getId, clubId)
                .setSql("member_count = member_count + 1"));
        log.info("加入跑团成功：clubId={}, userId={}", clubId, caller);
        club.setMemberCount(club.getMemberCount() == null ? 1 : club.getMemberCount() + 1);
        return buildClubDTOs(List.of(club)).get(0);
    }

    /**
     * 退出跑团（TASK-275）：团长且有其他成员 → 1002 禁止直接退出（需先移交/解散）；
     * 仅剩团长一人 → 删成员行并置 status=0 解散、member_count 清零；
     * 普通成员 → 删成员行并原子递减 member_count。
     */
    @Transactional
    public void leaveClub(Long caller, Long clubId) {
        if (caller == null || clubId == null) {
            throw new IllegalArgumentException("caller 与 clubId 不能为空");
        }
        ClubMember member = clubMemberMapper.selectOne(new LambdaQueryWrapper<ClubMember>()
                .eq(ClubMember::getUserId, caller)
                .eq(ClubMember::getClubId, clubId)
                .last("LIMIT 1"));
        if (member == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND, "尚未加入该跑团");
        }
        if (ClubMember.ROLE_CAPTAIN.equals(member.getRole())) {
            Long others = clubMemberMapper.selectCount(new LambdaQueryWrapper<ClubMember>()
                    .eq(ClubMember::getClubId, clubId)
                    .ne(ClubMember::getUserId, caller));
            if (others != null && others > 0) {
                throw new BizException(ResultCode.FORBIDDEN, "团长需先移交团长职位或解散跑团后才能退出");
            }
            clubMemberMapper.deleteById(member.getId());
            runningClubMapper.update(null, new LambdaUpdateWrapper<RunningClub>()
                    .eq(RunningClub::getId, clubId)
                    .set(RunningClub::getStatus, RunningClub.STATUS_DISSOLVED)
                    .set(RunningClub::getMemberCount, 0)
                    .set(RunningClub::getUpdatedAt, LocalDateTime.now()));
            log.info("仅剩团长一人退团，跑团解散：clubId={}, captain={}", clubId, caller);
            return;
        }
        clubMemberMapper.deleteById(member.getId());
        runningClubMapper.update(null, new LambdaUpdateWrapper<RunningClub>()
                .eq(RunningClub::getId, clubId)
                .set(RunningClub::getUpdatedAt, LocalDateTime.now())
                .setSql("member_count = member_count - 1"));
        log.info("退出跑团成功：clubId={}, userId={}", clubId, caller);
    }

    /**
     * 我的跑团（TASK-275）：查询 caller 当前加入的跑团（未入团返回 null）。
     */
    public ClubDTO getMyClub(Long caller) {
        if (caller == null) {
            throw new IllegalArgumentException("caller 不能为空");
        }
        ClubMember member = findMembership(caller);
        if (member == null) {
            return null;
        }
        RunningClub club = runningClubMapper.selectById(member.getClubId());
        if (club == null) {
            return null;
        }
        return buildClubDTOs(List.of(club)).get(0);
    }

    // ==================== 团长移交（TASK-276 R1） ====================

    /**
     * 团长移交（TASK-276 R1）：把 {@code clubId} 的团长职位从操作者移交给同团成员 {@code targetUserId}。
     *
     * <p>校验链（全部失败即拒绝，零写操作）：</p>
     * <ol>
     *   <li>跑团存在且未解散 → 否则 3001（RECORD_NOT_FOUND，404）；</li>
     *   <li>操作者须为本团在册成员 → 否则 3001；</li>
     *   <li>操作者须为<b>在任团长</b>（role=CAPTAIN）→ 否则 1002（FORBIDDEN，「仅团长可移交」）；</li>
     *   <li>目标须为本团其他成员：目标不在本团、或目标就是操作者本人 → 3001（沿用 spec R1
     *       目标侧统一 3001 的口径，common 错误码枚举不在本任务白名单内，禁新增 CLUB_* 段）。</li>
     * </ol>
     *
     * <p>写操作：<b>同事务双 role 更新</b>（操作者 CAPTAIN→MEMBER、目标 MEMBER→CAPTAIN），
     * {@code running_club.member_count} 与 {@code creator_id} 均不变——移交只换团长身份，
     * 不增减成员、不改「创建者」历史归属（成员数一致性由 {@link #listMembers} 与
     * {@code member_count} 分别承载）。</p>
     *
     * @param operatorId   操作者 userId（网关注入身份）
     * @param clubId       跑团 id
     * @param targetUserId 目标成员 userId
     * @return 移交后的跑团 DTO（成员数等口径与 {@link #getClub} 一致）
     */
    @Transactional
    public ClubDTO transferCaptain(Long operatorId, Long clubId, Long targetUserId) {
        if (operatorId == null || clubId == null || targetUserId == null) {
            throw new IllegalArgumentException("operatorId、clubId 与 targetUserId 不能为空");
        }
        RunningClub club = requireActiveClub(clubId, "跑团不存在或已解散");
        ClubMember operator = findMembershipInClub(clubId, operatorId);
        if (operator == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND, "尚未加入该跑团");
        }
        if (!ClubMember.ROLE_CAPTAIN.equals(operator.getRole())) {
            throw new BizException(ResultCode.FORBIDDEN, "仅团长可移交团长职位");
        }
        if (targetUserId.equals(operatorId)) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND, "不能把团长职位移交给自己");
        }
        ClubMember target = findMembershipInClub(clubId, targetUserId);
        if (target == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND, "移交目标不是本团在册成员");
        }

        // 同事务双 role 更新（本地事务覆盖两行 UPDATE，member_count 不变）
        clubMemberMapper.update(null, new LambdaUpdateWrapper<ClubMember>()
                .eq(ClubMember::getId, operator.getId())
                .set(ClubMember::getRole, ClubMember.ROLE_MEMBER));
        clubMemberMapper.update(null, new LambdaUpdateWrapper<ClubMember>()
                .eq(ClubMember::getId, target.getId())
                .set(ClubMember::getRole, ClubMember.ROLE_CAPTAIN));
        log.info("团长移交成功：clubId={}, from={}, to={}", clubId, operatorId, targetUserId);
        return buildClubDTOs(List.of(club)).get(0);
    }

    /**
     * 跑团成员数批查（TASK-276 R4，供 internal 端点 {@code GET /internal/clubs/member-counts}）：
     * 一次 {@code selectBatchIds} 回表，返回 clubId→member_count；不存在的跑团不出现在结果中
     * （消费方 leaderboard-service 对缺项降级 0）。
     */
    public Map<Long, Integer> listMemberCounts(List<Long> clubIds) {
        if (clubIds == null || clubIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = clubIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<RunningClub> clubs = runningClubMapper.selectBatchIds(distinct);
        if (clubs == null || clubs.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>(clubs.size());
        for (RunningClub club : clubs) {
            counts.put(club.getId(), club.getMemberCount() == null ? 0 : club.getMemberCount());
        }
        return counts;
    }

    // ==================== 内部工具 ====================

    /** 查询用户当前成员记录（一人一团：全仓至多一行） */
    private ClubMember findMembership(Long userId) {
        return clubMemberMapper.selectOne(new LambdaQueryWrapper<ClubMember>()
                .eq(ClubMember::getUserId, userId)
                .last("LIMIT 1"));
    }

    /** 查询用户在某跑团内的成员记录（移交校验用：限定 clubId，防跨团误判） */
    private ClubMember findMembershipInClub(Long clubId, Long userId) {
        return clubMemberMapper.selectOne(new LambdaQueryWrapper<ClubMember>()
                .eq(ClubMember::getClubId, clubId)
                .eq(ClubMember::getUserId, userId)
                .last("LIMIT 1"));
    }

    /** 活跃跑团存在性校验：不存在或已解散（status=0）→ 3001（404） */
    private RunningClub requireActiveClub(Long clubId, String message) {
        if (clubId == null) {
            throw new IllegalArgumentException("clubId 不能为空");
        }
        RunningClub club = runningClubMapper.selectById(clubId);
        if (club == null || club.getStatus() == null || club.getStatus() != RunningClub.STATUS_ACTIVE) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND, message);
        }
        return club;
    }

    /** 批量组装 ClubDTO：一次 selectBatchIds 回表补齐团长昵称（用户缺失时昵称留空） */
    private List<ClubDTO> buildClubDTOs(List<RunningClub> clubs) {
        List<Long> creatorIds = clubs.stream()
                .map(RunningClub::getCreatorId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, String> nicknames = resolveNicknames(creatorIds);

        List<ClubDTO> result = new ArrayList<>(clubs.size());
        for (RunningClub club : clubs) {
            ClubDTO dto = new ClubDTO();
            dto.setId(club.getId());
            dto.setName(club.getName());
            dto.setDescription(club.getDescription());
            dto.setBadgeUrl(club.getBadgeUrl());
            dto.setCreatorId(club.getCreatorId());
            dto.setCreatorNickname(nicknames.get(club.getCreatorId()));
            dto.setMemberCount(club.getMemberCount());
            dto.setStatus(club.getStatus());
            dto.setCreatedAt(club.getCreatedAt());
            result.add(dto);
        }
        return result;
    }

    /** 批量拉昵称（一次 selectBatchIds；用户不存在/查询失败时占位缺失，不阻断主链路） */
    private Map<Long, String> resolveNicknames(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nicknames = new HashMap<>();
        List<User> users = userMapper.selectBatchIds(userIds);
        if (users != null) {
            for (User u : users) {
                nicknames.put(u.getId(), u.getNickname());
            }
        }
        return nicknames;
    }
}
