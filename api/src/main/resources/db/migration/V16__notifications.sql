create table if not exists notifications
(
    id                uuid primary key,
    created_at        timestamp with time zone not null,
    expires_at        timestamp with time zone null,
    notification_type int                      not null,
    title             text                     not null,
    content           text                     not null
)