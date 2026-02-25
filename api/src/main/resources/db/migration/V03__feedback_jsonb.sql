alter table messages
    drop constraint fk_messages_feedback__id;

alter table messages
    drop column if exists feedback;

alter table messages
    add column feedback jsonb null;

drop table feedbacks;