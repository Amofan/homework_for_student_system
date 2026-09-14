create table app_user (
    id bigint auto_increment primary key,
    username varchar(64) not null,
    password_hash varchar(100) not null,
    role varchar(32) not null,
    enabled boolean not null default true,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_app_user_username unique (username)
);

create table teacher (
    id bigint auto_increment primary key,
    user_id bigint not null,
    display_name varchar(64) not null,
    school_name varchar(128),
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_teacher_user unique (user_id),
    constraint fk_teacher_user foreign key (user_id) references app_user(id)
);

create table school_class (
    id bigint auto_increment primary key,
    teacher_id bigint not null,
    class_code varchar(64) not null,
    name varchar(64) not null,
    grade tinyint,
    semester varchar(32),
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    deleted_at timestamp(3),
    constraint uk_class_teacher_code unique (teacher_id, class_code),
    constraint fk_class_teacher foreign key (teacher_id) references teacher(id)
);

create table student (
    id bigint auto_increment primary key,
    class_id bigint not null,
    student_no varchar(64) not null,
    name varchar(64) not null,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    deleted_at timestamp(3),
    constraint uk_student_class_no unique (class_id, student_no),
    constraint fk_student_class foreign key (class_id) references school_class(id)
);

create index idx_class_teacher on school_class(teacher_id);
create index idx_student_class on student(class_id);
