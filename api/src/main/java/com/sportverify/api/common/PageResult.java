package com.sportverify.api.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 通用分页结果（api 契约）。
 *
 * <p>api 模块不依赖 MyBatis-Plus，跨服务分页统一使用本类型替代 MP 的 Page；
 * 各服务在 Controller/Service 层做一次薄转换（字段语义与 MP Page 对齐：
 * current/size/total/records）。首个使用方为好友列表（user-api）。</p>
 *
 * @param <T> 列表项类型
 */
@Data
@NoArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页（从 1 开始） */
    private long current;

    /** 每页大小 */
    private long size;

    /** 总条数 */
    private long total;

    /** 当前页数据 */
    private List<T> records;

    public PageResult(long current, long size, long total, List<T> records) {
        this.current = current;
        this.size = size;
        this.total = total;
        this.records = records;
    }
}
