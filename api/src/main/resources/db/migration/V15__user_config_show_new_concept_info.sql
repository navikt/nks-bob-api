alter table user_configs
    add column show_new_concept_info boolean not null default false;

update user_configs
set show_new_concept_info = true
where show_new_concept_info = false;