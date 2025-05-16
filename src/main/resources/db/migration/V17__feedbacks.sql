alter table messages
    drop column feedback;

create table if not exists feedbacks
(
    id         uuid primary key,
    created_at timestamp with time zone not null,
    message    uuid                     not null,
    options    text[]                   not null,
    comment    text                     null     default null,
    resolved   boolean                  not null default false,

    constraint fk_feedbacks_message__id
        foreign key (message) references messages (id)
            on delete cascade
            on update cascade
)

