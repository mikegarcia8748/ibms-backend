-- =====================================================================
--  Per-user notification subscriptions.
--
--  A sysadmin opts each user (in practice the secretary/finance/manager
--  staff) into the specific events they should be emailed about. Recipients
--  for an event are resolved by joining ACTIVE users with a non-null email
--  against this table. event_type is a free TEXT key matching
--  NotificationEvent.key (e.g. 'store.created') — kept as TEXT for the same
--  reason email_log.type and activities.action are: the set of events evolves
--  in code, not the schema. The existing email_log table (V1) is the outbox
--  these subscriptions feed.
-- =====================================================================
CREATE TABLE user_notification_subscriptions (
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_type)
);

-- Recipient resolution filters by event_type, so index it.
CREATE INDEX idx_user_notif_sub_event ON user_notification_subscriptions (event_type);
