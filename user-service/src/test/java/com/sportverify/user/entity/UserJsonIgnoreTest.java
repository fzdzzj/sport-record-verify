package com.sportverify.user.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户实体序列化安全防御测试（TASK-263，G19 User passwordHash 防御性注解）。
 *
 * <p>验证 User 实体的 passwordHash 字段标注 @JsonIgnore，
 * 经 Jackson ObjectMapper 序列化为 JSON 字符串时，输出绝对不包含 passwordHash 字段与敏感哈希值。</p>
 */
class UserJsonIgnoreTest {

    @Test
    @DisplayName("序列化 User 实体时不包含 passwordHash 字段")
    void serializeUser_ignoresPasswordHash() throws Exception {
        User user = new User();
        user.setId(1001L);
        user.setPhone("13800000000");
        user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuvwxyz1234567890ABCDEFGHIJKLM");
        user.setNickname("runner_test");
        user.setRole(User.ROLE_USER);
        user.setStatus(1);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(user);

        assertFalse(json.contains("passwordHash"), "序列化 JSON 文本中不应包含 passwordHash 字段名");
        assertFalse(json.contains("abcdefghijklmnopqrstuvwxyz"), "序列化 JSON 文本中不应包含密码哈希值");
        assertTrue(json.contains("13800000000"), "序列化 JSON 文本中应正常包含其他常规业务字段");
        assertTrue(json.contains("runner_test"), "序列化 JSON 文本中应正常包含昵称字段");
    }
}
