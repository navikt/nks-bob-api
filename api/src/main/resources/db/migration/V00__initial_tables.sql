create table if not exists conversations (
    id uuid primary key,
    title varchar (255) not null,
    created_at timestamp with time zone not null,
    owner varchar (255) not null
);

create table if not exists feedbacks (
    id uuid primary key,
    liked boolean not null,
    created_at timestamp with time zone not null
);

create table if not exists messages (
    id uuid primary key,
    content text not null,
    conversation uuid not null,
    feedback uuid null,
    created_at timestamp with time zone not null,
    message_type int not null,
    message_role int not null,
    created_by varchar (255) not null,
    constraint fk_messages_conversation__id
    foreign key (conversation) references conversations (id)
        on delete restrict
        on update restrict,
    constraint fk_messages_feedback__id
    foreign key (feedback) references feedbacks (id)
        on delete set null
        on update cascade
);

create table if not exists citations (
    id uuid primary key,
    message uuid not null,
    text text not null,
    article varchar (255) not null,
    title varchar (255) not null,
    "section" varchar (255) not null,
    created_at timestamp with time zone not null,
    constraint fk_citations_message__id
    foreign key (message) references messages (id)
    on delete restrict
    on update restrict
);
