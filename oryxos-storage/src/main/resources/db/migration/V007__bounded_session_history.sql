-- Keep all history durable; index the bounded runtime views without rewriting earlier migrations.
CREATE INDEX messages_turn ON messages(session_id,turn_id,seq);
CREATE INDEX messages_user_sequence ON messages(session_id,seq DESC,turn_id) WHERE role='USER';
