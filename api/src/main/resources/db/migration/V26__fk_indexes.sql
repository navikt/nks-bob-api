CREATE INDEX IF NOT EXISTS idx_messages_conversation
    ON messages(conversation);

CREATE INDEX IF NOT EXISTS idx_feedbacks_message
    ON feedbacks(message);

CREATE INDEX IF NOT EXISTS idx_ignored_words_conversation
    ON ignored_words(conversation);