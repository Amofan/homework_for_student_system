create table knowledge_point (
    id bigint auto_increment primary key,
    teacher_id bigint not null,
    parent_id bigint,
    code varchar(64) not null,
    name varchar(128) not null,
    grade tinyint not null,
    active boolean not null default true,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_knowledge_teacher_code unique (teacher_id, code),
    constraint fk_knowledge_teacher foreign key (teacher_id) references teacher(id),
    constraint fk_knowledge_parent foreign key (parent_id) references knowledge_point(id)
);

create table question (
    id bigint auto_increment primary key,
    teacher_id bigint not null,
    question_code varchar(64) not null,
    type varchar(32) not null,
    content varchar(4000) not null,
    standard_answer varchar(4000),
    total_score int not null,
    primary_knowledge_point_id bigint not null,
    accepted_answers varchar(4000) not null,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    deleted_at timestamp(3),
    constraint uk_question_teacher_code unique (teacher_id, question_code),
    constraint fk_question_teacher foreign key (teacher_id) references teacher(id),
    constraint fk_question_primary_knowledge foreign key (primary_knowledge_point_id) references knowledge_point(id)
);

create table question_knowledge_point (
    question_id bigint not null,
    knowledge_point_id bigint not null,
    is_primary boolean not null default false,
    primary key (question_id, knowledge_point_id),
    constraint fk_qkp_question foreign key (question_id) references question(id),
    constraint fk_qkp_knowledge foreign key (knowledge_point_id) references knowledge_point(id)
);

create table rubric_item (
    id bigint auto_increment primary key,
    question_id bigint not null,
    order_no int not null,
    title varchar(128) not null,
    criteria varchar(1000) not null,
    max_score int not null,
    constraint uk_rubric_question_order unique (question_id, order_no),
    constraint fk_rubric_question foreign key (question_id) references question(id)
);

create index idx_knowledge_teacher on knowledge_point(teacher_id);
create index idx_question_teacher on question(teacher_id);
create index idx_qkp_knowledge on question_knowledge_point(knowledge_point_id);
