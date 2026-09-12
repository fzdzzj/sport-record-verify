-- ============================================================
-- 路网表结构（mapmatch-service 道路拓扑匹配，见 ADR-0006）
-- 由 scripts/mapmatch/import-road-network.sh 在数据导入前执行：
-- 全部 IF NOT EXISTS / IF NOT EXISTS，幂等可重跑；数据重灌由脚本侧 TRUNCATE 完成
-- ============================================================

-- PostGIS 扩展：提供 geometry 类型 / GIST 索引 / ST_* 空间函数
CREATE EXTENSION IF NOT EXISTS postgis;

-- 道路边表：一行 = 一条 OSM way 折线（道路边）。匹配算法以「边」为吸附目标，
-- 不建顶点表/连通图——最近边投影只需要折线几何与点到线段垂距，拓扑连通性是 HMM 进阶的事
CREATE TABLE IF NOT EXISTS road_edge (
    id           BIGSERIAL PRIMARY KEY,          -- 代理主键（重灌 RESTART IDENTITY）
    osm_way_id   BIGINT       NOT NULL,          -- OSM way 原始 ID（溯源/排查用，不唯一约束：重灌期由 TRUNCATE 保证干净）
    name         VARCHAR(128),                   -- 道路名（OSM name 标签，可空：无名支路/匝道）
    highway_type VARCHAR(32)  NOT NULL,          -- 道路类型：OSM highway 标签原值（primary/residential/cycleway...）
    road_level   SMALLINT     NOT NULL,          -- 道路等级 1-5（导入器按类型归档，供 per-road-type 阈值讲设计）
    geom         GEOMETRY(LINESTRING, 4326) NOT NULL  -- 道路折线（WGS84 经纬度，与轨迹点同坐标系免投影转换）
);

-- GIST 空间索引：候选边查询 ST_DWithin(geom, 点, 半径) 的性能前提——
-- 无索引则每次匹配对全表两两求距，索引后按 R-Tree 包围盒剪枝，单点查询毫秒级
CREATE INDEX IF NOT EXISTS idx_road_edge_geom ON road_edge USING GIST (geom);

COMMENT ON TABLE  road_edge              IS 'OSM 路网道路边表（真实 OSM 单城市切片导入，非手工假数据）';
COMMENT ON COLUMN road_edge.road_level   IS '道路等级：1=高速/快速 2=主干 3=次干 4=支路/居住 5=步行/骑行';
COMMENT ON COLUMN road_edge.geom         IS '道路折线 LINESTRING(WGS84)，匹配时 ST_DWithin 走 GIST 索引预筛候选边';
