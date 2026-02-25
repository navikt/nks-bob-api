alter table messages
    drop constraint fk_messages_conversation__id;

alter table messages
    add constraint fk_messages_conversation__id
        foreign key (conversation) references conversations (id)
            on delete cascade
            on update cascade;

alter table messages
    drop constraint fk_messages_feedback__id;

alter table messages
    add constraint fk_messages_feedback__id
        foreign key (feedback) references feedbacks (id)
            on delete cascade
            on update cascade;

alter table citations
    drop constraint fk_citations_message__id;

alter table citations
    add constraint fk_citations_message__id
        foreign key (message) references messages (id)
            on delete cascade
            on update cascade;
