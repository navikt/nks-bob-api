create table if not exists ignored_words
(
    id             uuid primary key,
    created_at     timestamp with time zone not null,
    updated_at     timestamp with time zone not null,
    conversation   uuid                     null,
    value          text                     not null,
    validation_type text                     not null,

    constraint fk_ignored_words_conversation__id
        foreign key (conversation) references conversations (id)
            on delete set null
            on update set null
)

