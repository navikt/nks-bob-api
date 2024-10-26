alter table messages
    add column pending boolean default false not null;
