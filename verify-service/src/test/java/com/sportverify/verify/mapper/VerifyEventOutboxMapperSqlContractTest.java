package com.sportverify.verify.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * outbox 取批资格契约判别（TASK-142）：取批 SQL 必须以「当前重试上限」过滤重试耗尽行。
 *
 * <p>本类只守卫 Mapper 取批查询的<strong>资格契约</strong>（注解 SQL 文本 + 参数名/顺序），
 * 使「耗尽行不占发送批次」这一条件在纯 JVM 单测中可执行、可回归；
 * <strong>真实 SQL 行为红/绿</strong>由隔离 scratch MySQL 上执行的
 * {@code work/mailbox/verification/task142-outbox-poison-sql.sql} 实证，
 * 不由 Mockito 预制过滤结果冒充。</p>
 */
class VerifyEventOutboxMapperSqlContractTest {

    @Test
    void selectPendingBatch_qualifiedByRetryThreshold() throws Exception {
        // 旧签名只有 (int limit)：未修复实现此处直接 NoSuchMethodException，即行为红。
        Method m = VerifyEventOutboxMapper.class.getMethod("selectPendingBatch", int.class, int.class);

        Select select = m.getAnnotation(Select.class);
        assertNotNull(select, "selectPendingBatch 应为 @Select 注解查询");
        String sql = String.join(" ", select.value());
        assertTrue(sql.contains("status = 'PENDING'"), "取批仍只取 PENDING 行，实测 SQL=" + sql);
        assertTrue(sql.contains("retry_count < #{maxRetry}"),
                "取批须排除 retry_count 达到当前上限的耗尽行，实测 SQL=" + sql);
        assertTrue(sql.contains("ORDER BY id"), "取批仍按 id 先到先得，实测 SQL=" + sql);
        assertTrue(sql.contains("LIMIT #{limit}"), "取批仍受批次上限约束，实测 SQL=" + sql);

        Parameter[] params = m.getParameters();
        assertEquals(2, params.length, "取批查询须同时接收批次上限与当前重试上限");
        assertEquals("limit", params[0].getAnnotation(Param.class).value(), "第 1 参数应为批次上限");
        assertEquals("maxRetry", params[1].getAnnotation(Param.class).value(), "第 2 参数应为当前重试上限");
    }
}
