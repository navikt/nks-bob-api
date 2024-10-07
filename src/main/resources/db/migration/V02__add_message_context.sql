alter table messages
    drop column if exists context;

alter table messages
    add column context jsonb default '[]' not null;