alter table messages
    add column errors jsonb default '[]' not null;
