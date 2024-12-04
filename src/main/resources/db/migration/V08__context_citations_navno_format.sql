update messages
set citations =
        (select case
                    when jsonb_array_length(citations) > 0
                        then jsonb_agg(
                            jsonb_build_object(
                                    'text', citation ->> 'text',
                                    'sourceId', 0))
                    else citations
                    end
         from jsonb_array_elements(citations) as citation)
where jsonb_array_length(citations) > 0;

update messages
set context =
        (select case
                    when jsonb_array_length(context) > 0
                        then jsonb_agg(
                            jsonb_build_object(
                                    'content', context_elem ->> 'content',
                                    'title', context_elem -> 'metadata' ->> 'Title',
                                    'ingress', context_elem -> 'metadata' ->> 'Section',
                                    'source', 'nks',
                                    'url', context_elem -> 'metadata' ->> 'KnowledgeArticle_QuartoUrl',
                                    'anchor', context_elem -> 'metadata' ->> 'Fragment',
                                    'articleId', context_elem -> 'metadata' ->> 'KnowledgeArticleId',
                                    'articleColumn', context_elem -> 'metadata' ->> 'ContentColumn',
                                    'lastModified', null,
                                    'semanticSimilarity',
                                    (context_elem -> 'metadata' ->> 'SemanticSimilarity')::float))
                    else context
                    end
         from jsonb_array_elements(context) as context_elem)
where jsonb_array_length(context) > 0;