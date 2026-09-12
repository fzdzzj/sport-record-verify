package com.sportverify.verify.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 规则快照编解码工具（VerifyProperties &lt;-&gt; rule_version.rules_json）。
 *
 * <p>为什么不用注入的 ObjectMapper bean：快照格式要求跨时间稳定——库里存着历史版本，
 * 若全局 ObjectMapper 配置变化（命名策略/特性开关）会导致旧快照解析行为漂移，
 * 故绑定独立实例，与业务序列化隔离。</p>
 *
 * <p>反序列化忽略未知字段：向前兼容——老版本服务读新版本写入的快照（多出新阈值字段）
 * 不报错，未识别字段回落本地默认值，保证灰度可回滚到旧代码。</p>
 */
public final class RulesSnapshotCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RulesSnapshotCodec() {
    }

    /** 创建版本时把当前规则+阈值序列化为 rules_json 落库 */
    public static String toJson(VerifyProperties props) {
        try {
            return MAPPER.writeValueAsString(props);
        } catch (Exception e) {
            throw new IllegalStateException("规则快照序列化失败", e);
        }
    }

    /** 灰度分支执行前把 rules_json 反序列化为规则执行对象；解析失败由调用方降级基线 */
    public static VerifyProperties fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("rules_json 为空");
        }
        try {
            return MAPPER.readValue(json, VerifyProperties.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("rules_json 解析失败", e);
        }
    }
}
