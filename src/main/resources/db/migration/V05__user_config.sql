create table if not exists user_configs
(
    id              uuid primary key,
    created_at      timestamp with time zone not null,
    nav_ident       varchar(255)             not null unique,
    show_start_info boolean                  not null default true
);

