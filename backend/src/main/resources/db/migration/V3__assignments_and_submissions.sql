create table assignment (
    id bigint auto_increment primary key,
    teacher_id bigint not null,
    class_id bigint not null,
    title varchar(128) not null,
    status varchar(32) not null default 'DRAFT',
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    deleted_at timestamp(3),
    constraint fk_assignment_teacher foreign key (teacher_id) references teacher(id),
    constraint fk_assignment_class foreign key (class_id) references school_class(id)
);

create table assignment_question (
    assignment_id bigint not null,
    question_id bigint not null,
    question_order int not null,
    primary key (assignment_id, question_id),
    constraint uk_assignment_question_order unique (assignment_id, question_order),
    constraint fk_aq_assignment foreign key (assignment_id) references assignment(id),
    constraint fk_aq_question foreign key (question_id) references question(id)
);

create table submission (
    id bigint auto_increment primary key,
    assignment_id bigint not null,
    student_id bigint not null,
    status varchar(32) not null default 'IMPORTED',
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_submission_assignment_student unique (assignment_id, student_id),
    constraint fk_submission_assignment foreign key (assignment_id) references assignment(id),
    constraint fk_submission_student foreign key (student_id) references student(id)
);

create table student_answer (
    id bigint auto_increment primary key,
    submission_id bigint not null,
    question_id bigint not null,
    answer_content varchar(8000) not null,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_answer_submission_question unique (submission_id, question_id),
    constraint fk_answer_submission foreign key (submission_id) references submission(id),
    constraint fk_answer_question foreign key (question_id) references question(id)
);

create index idx_assignment_teacher_class on assignment(teacher_id, class_id);
create index idx_submission_assignment on submission(assignment_id);
create index idx_answer_question on student_answer(question_id);
