alter table messages
    add column follow_up jsonb default '[]' not null;
