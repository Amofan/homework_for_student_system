create table ai_grading_task (
    id bigint auto_increment primary key,
    answer_id bigint not null,
    status varchar(32) not null default 'PENDING',
    attempt_count int not null default 0,
    prompt_version varchar(32) not null default 'v1',
    model_name varchar(128),
    next_attempt_at timestamp(3),
    last_error_code varchar(64),
    sanitized_response varchar(8000),
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_ai_task_answer unique (answer_id),
    constraint fk_ai_task_answer foreign key (answer_id) references student_answer(id)
);

create index idx_ai_task_claim on ai_grading_task(status, next_attempt_at);
