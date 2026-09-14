create table grading_result (
    id bigint auto_increment primary key,
    answer_id bigint not null,
    source varchar(16) not null,
    suggested_score int not null,
    confirmed_score int,
    error_type varchar(64) not null,
    teacher_explanation varchar(4000),
    student_feedback varchar(4000),
    score_details varchar(8000) not null,
    status varchar(32) not null default 'PENDING_REVIEW',
    version int not null default 0,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_grading_answer unique (answer_id),
    constraint fk_grading_answer foreign key (answer_id) references student_answer(id)
);

create table teacher_review (
    id bigint auto_increment primary key,
    result_id bigint not null,
    teacher_id bigint not null,
    decision varchar(16) not null,
    final_score int not null,
    final_error_type varchar(64) not null,
    feedback varchar(4000),
    reason varchar(1000),
    created_at timestamp(3) not null default current_timestamp(3),
    constraint uk_review_result unique (result_id),
    constraint fk_review_result foreign key (result_id) references grading_result(id),
    constraint fk_review_teacher foreign key (teacher_id) references teacher(id)
);

create table grading_audit (
    id bigint auto_increment primary key,
    result_id bigint not null,
    teacher_id bigint,
    action varchar(64) not null,
    detail_json varchar(8000) not null,
    created_at timestamp(3) not null default current_timestamp(3),
    constraint fk_audit_result foreign key (result_id) references grading_result(id),
    constraint fk_audit_teacher foreign key (teacher_id) references teacher(id)
);

create index idx_grading_status on grading_result(status);
create index idx_review_teacher on teacher_review(teacher_id);
