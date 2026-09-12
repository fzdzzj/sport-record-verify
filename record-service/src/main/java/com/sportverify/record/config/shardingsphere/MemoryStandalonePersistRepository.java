package com.sportverify.record.config.shardingsphere;

import org.apache.shardingsphere.mode.repository.standalone.StandalonePersistRepository;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版 Standalone 仓储（ShardingSphere 官方 SPI 契约的自实现）。
 *
 * <p>背景：shardingsphere-jdbc-core 5.4.1 未随包分发官方 Memory 仓储实现
 * （SPI-00001: No implementation class load ... with type Memory），且离线
 * 环境无法拉取官方构件；本类按 {@link StandalonePersistRepository} SPI 契约
 * 以内存 Map 实现同名字存储，注册于
 * {@code META-INF/services/org.apache.shardingsphere.mode.repository.standalone.StandalonePersistRepository}。</p>
 *
 * <p>语义：Standalone 模式下仅本进程使用，内存存储满足「分片路由元数据」场景
 * （无跨实例一致性要求，重启后 ShardingSphere 依据 YAML 重建元数据）。</p>
 */
public final class MemoryStandalonePersistRepository implements StandalonePersistRepository {

    /** 键值存储：{@code key -> value}，key 形如 {@code /metadata/...} */
    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public void init(Properties props) {
        // 内存实现无需初始化配置（与官方 Memory 仓储一致）
    }

    @Override
    public String getType() {
        return "Memory";
    }

    @Override
    public String getDirectly(String key) {
        return store.get(key);
    }

    @Override
    public List<String> getChildrenKeys(String key) {
        String prefix = key.endsWith("/") ? key : key + "/";
        return store.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .filter(part -> !part.contains("/"))
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isExisted(String key) {
        return store.containsKey(key);
    }

    @Override
    public void persist(String key, String value) {
        store.put(key, value);
    }

    @Override
    public void update(String key, String value) {
        store.put(key, value);
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public void close() {
        store.clear();
    }
}
