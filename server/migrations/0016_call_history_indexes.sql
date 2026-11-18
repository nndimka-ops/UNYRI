CREATE INDEX calls_caller_history_idx ON calls (caller_user_id, created_at DESC, id DESC) WHERE state = 'ended';
CREATE INDEX calls_callee_history_idx ON calls (callee_user_id, created_at DESC, id DESC) WHERE state = 'ended';
