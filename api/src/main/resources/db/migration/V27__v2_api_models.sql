alter table messages
    add column tools_v2 jsonb default '[]' not null;

alter table messages
    add column thinking text[] not null default array[]::varchar[];
