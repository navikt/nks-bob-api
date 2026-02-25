alter table feedbacks
    alter column message drop not null;

alter table feedbacks
    drop constraint fk_feedbacks_message__id;

alter table feedbacks
    add constraint fk_feedbacks_message__id
        foreign key (message) references messages (id)
            on delete set null
            on update set null
