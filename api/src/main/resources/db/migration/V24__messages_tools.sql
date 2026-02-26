alter table messages
    add column tools text[] not null default array[]::varchar[];
