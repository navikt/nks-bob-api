alter table messages
    add column citations jsonb default '[]' not null;

update messages m
set citations = (select jsonb_agg(jsonb_build_object(
        'text', c.text,
        'article', c.article,
        'section', c.section,
        'title', c.title))
                 from citations c
                 where c.message = m.id)
where m.id in (select distinct message from citations);

alter table citations
    drop constraint fk_citations_message__id;

drop table citations;