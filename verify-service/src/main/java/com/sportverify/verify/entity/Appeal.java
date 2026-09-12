package com.sportverify.verify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 申诉单实体（verify_db.appeal，规范「申诉提交」「终判改判/维持拒绝」）。
 *
 * <p>record_id 唯一（uk_record）：同一记录最多一张申诉单，
 * 重复提交由唯一键幂等（返回既有申诉单）。</p>
 */
@Data
@TableName("appeal")
public class Appeal {

    /** 申诉ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申诉的记录（唯一） */
    private Long recordId;

    /** 申诉用户 */
    private Long userId;

    /** 申诉理由 */
    private String reason;

    /** 状态：0 PENDING，1 RE_PASSED，2 RE_CONFIRMED（AppealStatus.code） */
    private Integer status;

    /** 复核操作人 */
    private String operator;

    /** 复核结论（详细） */
    private String recheckResult;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
