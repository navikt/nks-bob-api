alter table conversations
    add column updated_at timestamp with time zone null;

alter table feedbacks
    add column updated_at timestamp with time zone null;

alter table messages
    add column updated_at timestamp with time zone null;

alter table notifications
    add column updated_at timestamp with time zone null;

alter table user_configs
    add column updated_at timestamp with time zone null;
