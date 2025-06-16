alter table feedbacks
    add column resolved_importance int null default null;

alter table feedbacks
    add column resolved_note text null default null;

update feedbacks f
set resolved_importance = f.resolved_category
where id = f.id
  and f.resolved_category is not null;

update feedbacks f
set resolved_category = null
where id = f.id
  and f.resolved_category is not null;
