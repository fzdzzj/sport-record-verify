package com.sportverify.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跑团服务单元测试（TASK-275）：创建（一人一团拦截 / 重名拦截 / 团长落座）/
 * 广场分页（钳制与关键词）/ 详情与成员（昵称批量补齐）/ 加入（重复加入拦截、
 * 解散团拦截、原子累加、并发撞键兜底）/ 退出（团长移交拦截、仅剩团长解散、
 * 普通成员递减）/ 我的跑团（未入团 null）。
 *
 * <p>TASK-276 R1/R4 扩展：团长移交（成功双 role 交换、非团长 1002、操作者未入团 3001、
 * 目标非本团成员 / 目标未入团 / 目标是自己 3001、参数缺失 400）与成员数批查
 * （一次 selectBatchIds、缺项不出现、空入参不查库）。</p>
 *
 * <p>纯 Mockito：Mapper 全 mock，不依赖 MySQL；D7 家法 TableInfoHelper 显式初始化
 * 两实体 LambdaCache，支持独立单跑下的 Wrapper SQL 段断言。</p>
 */
class ClubServiceTest {

    private static final Long CALLER = 100L;
    private static final Long CLUB_ID = 77L;

    private RunningClubMapper runningClubMapper;
    private ClubMemberMapper clubMemberMapper;
    private UserMapper userMapper;
    private ClubService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), RunningClub.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ClubMember.class);
        runningClubMapper = mock(RunningClubMapper.class);
        clubMemberMapper = mock(ClubMemberMapper.class);
        userMapper = mock(UserMapper.class);
        service = new ClubService(runningClubMapper, clubMemberMapper, userMapper);
    }

    private static ClubCreateDTO createDto(String name) {
        ClubCreateDTO dto = new ClubCreateDTO();
        dto.setName(name);
        dto.setDescription("晨跑为主的城市跑团");
        dto.setBadgeUrl("https://cdn.example.com/badge.png");
        return dto;
    }

    private static RunningClub club(Long id, String name, int memberCount, int status) {
        RunningClub club = new RunningClub();
        club.setId(id);
        club.setName(name);
        club.setDescription("晨跑为主的城市跑团");
        club.setCreatorId(CALLER);
        club.setMemberCount(memberCount);
        club.setStatus(status);
        club.setCreatedAt(LocalDateTime.of(2026, 10, 19, 8, 0));
        return club;
    }

    private static User user(Long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setNickname(nickname);
        return u;
    }

    private void stubInsertAssignsClubId() {
        when(runningClubMapper.insert(any(RunningClub.class))).thenAnswer(inv -> {
            inv.getArgument(0, RunningClub.class).setId(CLUB_ID);
            return 1;
        });
        when(clubMemberMapper.insert(any(ClubMember.class))).thenReturn(1);
    }

    // ==================== 创建跑团 ====================

    /** 创建成功：插入 club（member_count=1）+ member（CAPTAIN），回填团长昵称。 */
    @Test
    void createClub_success_insertsClubAndCaptain() {
        stubInsertAssignsClubId();
        when(clubMemberMapper.selectOne(any())).thenReturn(null);
        when(runningClubMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(CALLER, "跑者甲")));

        ClubDTO dto = service.createClub(CALLER, createDto("黎明跑团"));

        assertEquals(CLUB_ID, dto.getId());
        assertEquals("黎明跑团", dto.getName());
        assertEquals(CALLER, dto.getCreatorId());
        assertEquals("跑者甲", dto.getCreatorNickname());
        assertEquals(1, dto.getMemberCount());
        assertEquals(RunningClub.STATUS_ACTIVE, dto.getStatus());

        ArgumentCaptor<RunningClub> clubCap = ArgumentCaptor.forClass(RunningClub.class);
        verify(runningClubMapper).insert(clubCap.capture());
        assertEquals(1, clubCap.getValue().getMemberCount());
        assertEquals(RunningClub.STATUS_ACTIVE, clubCap.getValue().getStatus());

        ArgumentCaptor<ClubMember> memberCap = ArgumentCaptor.forClass(ClubMember.class);
        verify(clubMemberMapper).insert(memberCap.capture());
        assertEquals(CLUB_ID, memberCap.getValue().getClubId());
        assertEquals(CALLER, memberCap.getValue().getUserId());
        assertEquals(ClubMember.ROLE_CAPTAIN, memberCap.getValue().getRole());
    }

    /** 已加入任何跑团 → 1002 拦截，不建团。 */
    @Test
    void createClub_callerAlreadyInClub_throws1002() {
        ClubMember existing = new ClubMember();
        existing.setUserId(CALLER);
        existing.setClubId(9L);
        when(clubMemberMapper.selectOne(any())).thenReturn(existing);

        BizException e = assertThrows(BizException.class,
                () -> service.createClub(CALLER, createDto("黎明跑团")));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(runningClubMapper, never()).insert(any(RunningClub.class));
    }

    /** 名称已被占用 → 3004 拦截，不建团。 */
    @Test
    void createClub_duplicateName_throws3004() {
        when(clubMemberMapper.selectOne(any())).thenReturn(null);
        when(runningClubMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> service.createClub(CALLER, createDto("黎明跑团")));
        assertEquals(ResultCode.IDEMPOTENT_CONFLICT.getCode(), e.getCode());
        verify(runningClubMapper, never()).insert(any(RunningClub.class));
    }

    /** 名称空白 → 400 非法参数。 */
    @Test
    void createClub_blankName_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.createClub(CALLER, createDto("  ")));
        assertThrows(IllegalArgumentException.class, () -> service.createClub(null, createDto("黎明跑团")));
    }

    // ==================== 跑团广场（分页查询） ====================

    /** 广场分页：size 超限钳制到 100，仅正常跑团，DTO 批量补昵称。 */
    @Test
    @SuppressWarnings("unchecked")
    void listClubs_clampsSize_andFillsNicknames() {
        Page<RunningClub> page = new Page<>(1, 100, 2);
        page.setRecords(List.of(club(CLUB_ID, "黎明跑团", 12, RunningClub.STATUS_ACTIVE),
                club(78L, "晚风跑团", 5, RunningClub.STATUS_ACTIVE)));
        when(runningClubMapper.selectPage(any(), any())).thenReturn(page);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(CALLER, "跑者甲")));

        PageResult<ClubDTO> result = service.listClubs(null, 1, 500);

        assertEquals(100L, result.getSize());
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals("跑者甲", result.getRecords().get(0).getCreatorNickname());

        ArgumentCaptor<Wrapper<RunningClub>> wrapperCap = ArgumentCaptor.forClass(Wrapper.class);
        verify(runningClubMapper).selectPage(any(), wrapperCap.capture());
        String sql = wrapperCap.getValue().getSqlSegment();
        assertTrue(sql.contains("STATUS") || sql.contains("status"), "应过滤正常跑团：" + sql);
        assertTrue(sql.contains("member_count DESC"), "应按成员数降序：" + sql);
        assertFalse(sql.contains("LIKE"), "无关键词不应拼接 LIKE：" + sql);
    }

    /** 关键词过滤：拼接名称 LIKE。 */
    @Test
    @SuppressWarnings("unchecked")
    void listClubs_keywordFiltersByName() {
        Page<RunningClub> page = new Page<>(1, 20, 0);
        page.setRecords(List.of());
        when(runningClubMapper.selectPage(any(), any())).thenReturn(page);

        service.listClubs(" 黎明 ", 2, 10);

        ArgumentCaptor<Wrapper<RunningClub>> wrapperCap = ArgumentCaptor.forClass(Wrapper.class);
        verify(runningClubMapper).selectPage(any(), wrapperCap.capture());
        String sql = wrapperCap.getValue().getSqlSegment();
        assertTrue(sql.contains("LIKE"), "关键词应拼接 LIKE：" + sql);
    }

    // ==================== 详情与成员 ====================

    /** 详情命中：回填团长昵称；已解散（status=0）→ 3001（404）。 */
    @Test
    void getClub_active_returnsDto_dissolved_throws() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 12, RunningClub.STATUS_ACTIVE));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(CALLER, "跑者甲")));
        ClubDTO dto = service.getClub(CLUB_ID);
        assertEquals("黎明跑团", dto.getName());
        assertEquals(12, dto.getMemberCount());

        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 0, RunningClub.STATUS_DISSOLVED));
        BizException e = assertThrows(BizException.class, () -> service.getClub(CLUB_ID));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
    }

    /** 详情缺失 → 3001（404）。 */
    @Test
    void getClub_missing_throws3001() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.getClub(CLUB_ID));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
    }

    /** 成员列表：按加入时间升序，批量回表补齐昵称（一次 selectBatchIds）。 */
    @Test
    void listMembers_batchFillsNicknames() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 2, RunningClub.STATUS_ACTIVE));
        ClubMember captain = new ClubMember();
        captain.setId(1L);
        captain.setClubId(CLUB_ID);
        captain.setUserId(CALLER);
        captain.setRole(ClubMember.ROLE_CAPTAIN);
        captain.setJoinedAt(LocalDateTime.of(2026, 10, 1, 7, 0));
        ClubMember member = new ClubMember();
        member.setId(2L);
        member.setClubId(CLUB_ID);
        member.setUserId(101L);
        member.setRole(ClubMember.ROLE_MEMBER);
        member.setJoinedAt(LocalDateTime.of(2026, 10, 2, 7, 0));
        when(clubMemberMapper.selectList(any())).thenReturn(List.of(captain, member));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(CALLER, "跑者甲"), user(101L, "跑友乙")));

        List<ClubMemberDTO> members = service.listMembers(CLUB_ID);

        assertEquals(2, members.size());
        assertEquals(ClubMember.ROLE_CAPTAIN, members.get(0).getRole());
        assertEquals("跑者甲", members.get(0).getNickname());
        assertEquals("跑友乙", members.get(1).getNickname());
        verify(userMapper).selectBatchIds(any());
    }

    /** 跑团不存在 → 成员列表 3001（404）。 */
    @Test
    void listMembers_clubMissing_throws3001() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.listMembers(CLUB_ID));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
    }

    // ==================== 加入跑团 ====================

    /** 加入成功：插入 MEMBER 行 + member_count 原子 +1（setSql 累加，不读改写）。 */
    @Test
    void joinClub_success_incrementsMemberCountAtomically() {
        when(clubMemberMapper.selectOne(any())).thenReturn(null);
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 1, RunningClub.STATUS_ACTIVE));
        when(clubMemberMapper.insert(any(ClubMember.class))).thenReturn(1);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());

        ClubDTO dto = service.joinClub(CALLER, CLUB_ID);

        assertEquals(2, dto.getMemberCount());
        ArgumentCaptor<ClubMember> memberCap = ArgumentCaptor.forClass(ClubMember.class);
        verify(clubMemberMapper).insert(memberCap.capture());
        assertEquals(ClubMember.ROLE_MEMBER, memberCap.getValue().getRole());
        assertEquals(CLUB_ID, memberCap.getValue().getClubId());

        ArgumentCaptor<LambdaUpdateWrapper<RunningClub>> updateCap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runningClubMapper).update(any(), updateCap.capture());
        assertTrue(updateCap.getValue().getSqlSet().contains("member_count = member_count + 1"),
                "成员数应原子累加：" + updateCap.getValue().getSqlSet());
    }

    /** 已加入任何跑团 → 1002 拦截（一人一团）。 */
    @Test
    void joinClub_alreadyInAnyClub_throws1002() {
        ClubMember existing = new ClubMember();
        existing.setUserId(CALLER);
        existing.setClubId(9L);
        when(clubMemberMapper.selectOne(any())).thenReturn(existing);

        BizException e = assertThrows(BizException.class, () -> service.joinClub(CALLER, CLUB_ID));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(clubMemberMapper, never()).insert(any(ClubMember.class));
    }

    /** 跑团缺失 / 已解散 → 3001（404）。 */
    @Test
    void joinClub_clubMissingOrDissolved_throws3001() {
        when(clubMemberMapper.selectOne(any())).thenReturn(null);
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(null);
        BizException missing = assertThrows(BizException.class, () -> service.joinClub(CALLER, CLUB_ID));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), missing.getCode());

        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 3, RunningClub.STATUS_DISSOLVED));
        BizException dissolved = assertThrows(BizException.class, () -> service.joinClub(CALLER, CLUB_ID));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), dissolved.getCode());
    }

    /** 并发窗口撞 uk_user_club 唯一键 → 1002 兜底（一人一团）。 */
    @Test
    void joinClub_duplicateKeyRace_throws1002() {
        when(clubMemberMapper.selectOne(any())).thenReturn(null);
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 1, RunningClub.STATUS_ACTIVE));
        when(clubMemberMapper.insert(any(ClubMember.class)))
                .thenThrow(new DuplicateKeyException("uk_user_club"));

        BizException e = assertThrows(BizException.class, () -> service.joinClub(CALLER, CLUB_ID));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(runningClubMapper, never()).update(any(), any());
    }

    // ==================== 退出跑团 ====================

    /** 普通成员退团：删成员行 + member_count 原子 -1，跑团保持正常。 */
    @Test
    void leaveClub_memberSuccess_decrementsMemberCount() {
        ClubMember member = new ClubMember();
        member.setId(2L);
        member.setClubId(CLUB_ID);
        member.setUserId(CALLER);
        member.setRole(ClubMember.ROLE_MEMBER);
        when(clubMemberMapper.selectOne(any())).thenReturn(member);

        service.leaveClub(CALLER, CLUB_ID);

        verify(clubMemberMapper).deleteById(2L);
        ArgumentCaptor<LambdaUpdateWrapper<RunningClub>> updateCap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runningClubMapper).update(any(), updateCap.capture());
        String sqlSet = updateCap.getValue().getSqlSet();
        assertTrue(sqlSet.contains("member_count = member_count - 1"), "应原子递减：" + sqlSet);
        assertFalse(sqlSet.contains("status"), "普通成员退团不解散：" + sqlSet);
    }

    /** 团长且有其他成员 → 1002 禁止直接退出（需先移交），零写操作。 */
    @Test
    void leaveClub_captainWithOthers_throws1002() {
        ClubMember captain = new ClubMember();
        captain.setId(1L);
        captain.setClubId(CLUB_ID);
        captain.setUserId(CALLER);
        captain.setRole(ClubMember.ROLE_CAPTAIN);
        when(clubMemberMapper.selectOne(any())).thenReturn(captain);
        when(clubMemberMapper.selectCount(any())).thenReturn(2L);

        BizException e = assertThrows(BizException.class, () -> service.leaveClub(CALLER, CLUB_ID));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(clubMemberMapper, never()).deleteById(any(Long.class));
        verify(runningClubMapper, never()).update(any(), any());
    }

    /** 仅剩团长一人退团：删成员行 + status=0 解散 + member_count 清零。 */
    @Test
    void leaveClub_captainAlone_dissolvesClub() {
        ClubMember captain = new ClubMember();
        captain.setId(1L);
        captain.setClubId(CLUB_ID);
        captain.setUserId(CALLER);
        captain.setRole(ClubMember.ROLE_CAPTAIN);
        when(clubMemberMapper.selectOne(any())).thenReturn(captain);
        when(clubMemberMapper.selectCount(any())).thenReturn(0L);

        service.leaveClub(CALLER, CLUB_ID);

        verify(clubMemberMapper).deleteById(1L);
        ArgumentCaptor<LambdaUpdateWrapper<RunningClub>> updateCap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runningClubMapper).update(any(), updateCap.capture());
        String sqlSet = updateCap.getValue().getSqlSet();
        assertTrue(sqlSet.contains("status"), "应置解散：" + sqlSet);
        assertTrue(sqlSet.contains("member_count"), "应清零成员数：" + sqlSet);
    }

    /** 未加入该跑团 → 3001（404）。 */
    @Test
    void leaveClub_notMember_throws3001() {
        when(clubMemberMapper.selectOne(any())).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.leaveClub(CALLER, CLUB_ID));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
        verify(clubMemberMapper, never()).deleteById(any(Long.class));
    }

    // ==================== 我的跑团 ====================

    /** 未入团 → null；已入团 → 跑团 DTO。 */
    @Test
    void getMyClub_returnsNullWhenNone_dtoWhenJoined() {
        when(clubMemberMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getMyClub(CALLER));

        ClubMember member = new ClubMember();
        member.setUserId(CALLER);
        member.setClubId(CLUB_ID);
        member.setRole(ClubMember.ROLE_MEMBER);
        when(clubMemberMapper.selectOne(any())).thenReturn(member);
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 5, RunningClub.STATUS_ACTIVE));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(CALLER, "跑者甲")));

        ClubDTO dto = service.getMyClub(CALLER);
        assertNotNull(dto);
        assertEquals("黎明跑团", dto.getName());
        assertEquals(5, dto.getMemberCount());
    }

    /** 入团记录在、跑团行缺失（脏数据）→ null 兜底。 */
    @Test
    void getMyClub_membershipWithoutClub_returnsNull() {
        ClubMember member = new ClubMember();
        member.setUserId(CALLER);
        member.setClubId(CLUB_ID);
        when(clubMemberMapper.selectOne(any())).thenReturn(member);
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(null);
        assertNull(service.getMyClub(CALLER));
    }

    // ==================== 团长移交（TASK-276 R1） ====================

    private static final Long TARGET = 101L;

    private static ClubMember membership(Long id, Long userId, String role) {
        ClubMember m = new ClubMember();
        m.setId(id);
        m.setClubId(CLUB_ID);
        m.setUserId(userId);
        m.setRole(role);
        m.setJoinedAt(LocalDateTime.of(2026, 10, 1, 7, 0));
        return m;
    }

    /**
     * 移交成功：同事务双 role 更新（操作者 CAPTAIN→MEMBER / 目标 MEMBER→CAPTAIN），
     * running_club 零写入（member_count 不变），返回 DTO 成员数不变。
     */
    @Test
    @SuppressWarnings("unchecked")
    void transferCaptain_success_swapsBothRolesAndKeepsMemberCount() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 2, RunningClub.STATUS_ACTIVE));
        when(clubMemberMapper.selectOne(any()))
                .thenReturn(membership(1L, CALLER, ClubMember.ROLE_CAPTAIN))
                .thenReturn(membership(2L, TARGET, ClubMember.ROLE_MEMBER));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(CALLER, "跑者甲")));

        ClubDTO dto = service.transferCaptain(CALLER, CLUB_ID, TARGET);

        assertEquals(2, dto.getMemberCount(), "移交不改变成员数");
        ArgumentCaptor<LambdaUpdateWrapper<ClubMember>> updateCap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(clubMemberMapper, times(2)).update(any(), updateCap.capture());
        List<LambdaUpdateWrapper<ClubMember>> wrappers = updateCap.getAllValues();
        // 双 role 更新：先降级操作者成员行（id=1 → MEMBER），再升级目标成员行（id=2 → CAPTAIN）。
        // getSqlSet()/getSqlSegment() 触发生成 SET 与 WHERE 段，参数随之物化到 paramNameValuePairs。
        String set0 = wrappers.get(0).getSqlSet();
        String where0 = wrappers.get(0).getSqlSegment();
        String set1 = wrappers.get(1).getSqlSet();
        String where1 = wrappers.get(1).getSqlSegment();
        assertTrue(set0.contains("role"), "应更新 role 字段：" + set0);
        assertTrue(where0.contains("id"), "降级行按成员行 id 定位：" + where0);
        assertTrue(wrappers.get(0).getParamNameValuePairs().containsValue(1L), "先降级操作者成员行");
        assertTrue(wrappers.get(0).getParamNameValuePairs().containsValue(ClubMember.ROLE_MEMBER));
        assertTrue(set1.contains("role"), "应更新 role 字段：" + set1);
        assertTrue(where1.contains("id"), "升级行按成员行 id 定位：" + where1);
        assertTrue(wrappers.get(1).getParamNameValuePairs().containsValue(2L), "再升级目标成员行");
        assertTrue(wrappers.get(1).getParamNameValuePairs().containsValue(ClubMember.ROLE_CAPTAIN));
        verify(runningClubMapper, never()).update(any(), any());
    }

    /** 非团长（普通成员）操作移交 → 1002 拦截，零写操作。 */
    @Test
    void transferCaptain_operatorNotCaptain_throws1002() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 2, RunningClub.STATUS_ACTIVE));
        when(clubMemberMapper.selectOne(any()))
                .thenReturn(membership(2L, CALLER, ClubMember.ROLE_MEMBER));

        BizException e = assertThrows(BizException.class,
                () -> service.transferCaptain(CALLER, CLUB_ID, TARGET));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(clubMemberMapper, never()).update(any(), any());
        verify(runningClubMapper, never()).update(any(), any());
    }

    /** 操作者未加入该跑团 → 3001（404），零写操作。 */
    @Test
    void transferCaptain_operatorNotMember_throws3001() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 2, RunningClub.STATUS_ACTIVE));
        when(clubMemberMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.transferCaptain(CALLER, CLUB_ID, TARGET));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
        verify(clubMemberMapper, never()).update(any(), any());
    }

    /** 目标不是本团在册成员（成员行缺失）→ 3001，零写操作。 */
    @Test
    void transferCaptain_targetNotMemberOfThisClub_throws3001() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 2, RunningClub.STATUS_ACTIVE));
        when(clubMemberMapper.selectOne(any()))
                .thenReturn(membership(1L, CALLER, ClubMember.ROLE_CAPTAIN))
                .thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.transferCaptain(CALLER, CLUB_ID, TARGET));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("不是本团在册成员"), "语义由 message 承载：" + e.getMessage());
        verify(clubMemberMapper, never()).update(any(), any());
    }

    /** 目标是操作者本人 → 3001，且不再回查目标成员行（校验链短路）、零写操作。 */
    @Test
    void transferCaptain_targetIsOperator_throws3001AndShortCircuits() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 2, RunningClub.STATUS_ACTIVE));
        when(clubMemberMapper.selectOne(any()))
                .thenReturn(membership(1L, CALLER, ClubMember.ROLE_CAPTAIN));

        BizException e = assertThrows(BizException.class,
                () -> service.transferCaptain(CALLER, CLUB_ID, CALLER));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("移交给自己"), "语义由 message 承载：" + e.getMessage());
        verify(clubMemberMapper, times(1)).selectOne(any());
        verify(clubMemberMapper, never()).update(any(), any());
    }

    /** 目标用户完全未入团（无任何成员行）→ 3001，零写操作。 */
    @Test
    void transferCaptain_targetWithoutAnyClub_throws3001() {
        when(runningClubMapper.selectById(CLUB_ID)).thenReturn(
                club(CLUB_ID, "黎明跑团", 2, RunningClub.STATUS_ACTIVE));
        // 操作者命中 → 目标（TARGET 从未入团）未命中
        when(clubMemberMapper.selectOne(any()))
                .thenReturn(membership(1L, CALLER, ClubMember.ROLE_CAPTAIN))
                .thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.transferCaptain(CALLER, CLUB_ID, TARGET));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
        verify(clubMemberMapper, times(2)).selectOne(any());
        verify(clubMemberMapper, never()).update(any(), any());
        verify(runningClubMapper, never()).update(any(), any());
    }

    /** 参数缺失 → 400 非法参数（不动库）。 */
    @Test
    void transferCaptain_nullArgs_throwIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.transferCaptain(null, CLUB_ID, TARGET));
        assertThrows(IllegalArgumentException.class,
                () -> service.transferCaptain(CALLER, null, TARGET));
        assertThrows(IllegalArgumentException.class,
                () -> service.transferCaptain(CALLER, CLUB_ID, null));
        verify(clubMemberMapper, never()).selectOne(any());
    }

    // ==================== 成员数批查（TASK-276 R4 内部端点支撑） ====================

    /** 批查回表：一次 selectBatchIds 返回 clubId→member_count，缺项不出现在结果中。 */
    @Test
    @SuppressWarnings("unchecked")
    void listMemberCounts_batchQueriesRealCounts() {
        when(runningClubMapper.selectBatchIds(any())).thenReturn(List.of(
                club(CLUB_ID, "黎明跑团", 12, RunningClub.STATUS_ACTIVE),
                club(78L, "晚风跑团", 5, RunningClub.STATUS_ACTIVE)));

        Map<Long, Integer> counts = service.listMemberCounts(List.of(CLUB_ID, 78L, 99L));

        assertEquals(2, counts.size());
        assertEquals(12, counts.get(CLUB_ID));
        assertEquals(5, counts.get(78L));
        assertNull(counts.get(99L), "不存在的跑团不出现在结果中（消费方降级 0）");
        verify(userMapper, never()).selectBatchIds(any());
    }

    /** 空入参 / 全 null 入参：直接空表，不查库。 */
    @Test
    void listMemberCounts_emptyArgs_returnsEmptyMap() {
        assertTrue(service.listMemberCounts(null).isEmpty());
        assertTrue(service.listMemberCounts(List.of()).isEmpty());
        verify(runningClubMapper, never()).selectBatchIds(any());
    }
}
