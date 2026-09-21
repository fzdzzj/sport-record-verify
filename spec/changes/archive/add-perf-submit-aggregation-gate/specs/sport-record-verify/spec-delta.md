# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（跨请求聚合闸门）。

## ADDED Requirements

### Requirement: 跨请求提交聚合须先复测

WHEN 评估把多个提交请求合并写库,
系统 SHALL 先在单记录批量插入已作为默认路径的前提下复测提交吞吐，SHALL NOT 在没有该复测证据时合并不同请求的写库。

#### Scenario: 无复测不做跨请求聚合

GIVEN 尚未在默认批量插入路径上复测 100 并发提交
WHEN 提出跨请求写聚合
THEN 不把该聚合作为已交付能力
AND 各提交请求仍独立落库

#### Scenario: 单记录批量不是跨请求聚合

GIVEN 一条记录内的多轨迹点批量插入已交付
WHEN 描述优化口径
THEN 不把它称为跨请求聚合
