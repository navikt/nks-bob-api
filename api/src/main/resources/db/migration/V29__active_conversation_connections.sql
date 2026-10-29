create table active_conversation_connections (
    id uuid primary key,
    conversation_id uuid not null,
    owner varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint fk_active_connections_conversation
        foreign key (conversation_id) references conversations(id) on delete cascade
);

create index idx_active_connections_conversation
    on active_conversation_connections(conversation_id);

create index idx_active_connections_updated_at
    on active_conversation_connections(updated_at);
