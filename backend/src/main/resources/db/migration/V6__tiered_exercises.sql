alter table question add column difficulty varchar(16) not null default 'MEDIUM';

create table exercise_set (
    id bigint auto_increment primary key,
    teacher_id bigint not null,
    class_id bigint not null,
    source_assignment_id bigint not null,
    title varchar(128) not null,
    status varchar(16) not null default 'DRAFT',
    approved_at timestamp(3),
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint fk_exercise_teacher foreign key (teacher_id) references teacher(id),
    constraint fk_exercise_class foreign key (class_id) references school_class(id),
    constraint fk_exercise_assignment foreign key (source_assignment_id) references assignment(id)
);

create table exercise_item (
    exercise_set_id bigint not null,
    question_id bigint not null,
    tier varchar(16) not null,
    sort_order int not null,
    primary key (exercise_set_id, question_id),
    constraint uk_exercise_tier_order unique (exercise_set_id, tier, sort_order),
    constraint fk_exercise_item_set foreign key (exercise_set_id) references exercise_set(id),
    constraint fk_exercise_item_question foreign key (question_id) references question(id)
);

create index idx_exercise_teacher on exercise_set(teacher_id);
create index idx_exercise_class on exercise_set(class_id);
create index idx_exercise_item_question on exercise_item(question_id);
