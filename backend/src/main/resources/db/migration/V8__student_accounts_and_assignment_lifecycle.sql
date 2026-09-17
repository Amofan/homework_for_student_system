-- 学生账号与作业生命周期。
--
-- 1. app_user 增加账号状态，用于表达“首次登录必须改密”；
-- 2. student 增加可空且唯一的 user_id 外键，旧学生数据保持未开通状态；
-- 3. assignment 增加发布/截止时间与乐观锁版本号。
--
-- 学生账号只能由教师开通，因此 user_id 允许为空：迁移后的历史学生仍然存在，
-- 教师执行“开通账号”后才建立关联。唯一约束允许多行 NULL，未开通的学生互不冲突。

alter table app_user add column account_status varchar(32) not null default 'ACTIVE';

alter table student add column user_id bigint;
alter table student add constraint uk_student_user unique (user_id);
alter table student add constraint fk_student_user foreign key (user_id) references app_user(id);

alter table assignment add column published_at timestamp(3);
alter table assignment add column due_at timestamp(3);
alter table assignment add column version int not null default 0;

create index idx_student_user on student(user_id);
create index idx_assignment_class_status on assignment(class_id, status);
