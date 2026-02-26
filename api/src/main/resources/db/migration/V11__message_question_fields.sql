alter table messages
    add column user_question text null;

alter table messages
    add column contextualized_question text null;
