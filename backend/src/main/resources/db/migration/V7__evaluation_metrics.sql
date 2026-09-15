-- 论文离线评测需要的采集字段。
--
-- 此前表结构能提供得分与错因的师生对照，但缺三样东西：
--   1. 模型调用的耗时与令牌用量（响应里的 usage 之前被适配器丢弃）；
--   2. 教师复核耗时（前端计时，服务端只负责落库）；
--   3. 模型原始错因。grading_result.error_type 会在教师复核时被最终错因覆盖，
--      覆盖后模型当初判成什么就查不回来了，而错因一致率正是要评测的指标之一，
--      因此原始值必须单独留一列，写一次不再改动。
--
-- 全部可空：失败的调用、无法计时的客户端、以及早于本次迁移的历史行都写 null。
-- 评测脚本把空值当作“缺失”而不是 0（见 evaluation/README.md）。

alter table grading_result
    add column ai_error_type varchar(64);

-- 一行一列：MySQL 允许 `add column a, add column b` 连写，H2 只接受单列子句，
-- 而测试库跑的是 H2。拆开写两边都能建，也免得将来换库再踩一次。
alter table ai_grading_task
    add column input_tokens int;

alter table ai_grading_task
    add column output_tokens int;

alter table ai_grading_task
    add column ai_seconds decimal(10, 3);

alter table teacher_review
    add column teacher_seconds decimal(10, 3);
