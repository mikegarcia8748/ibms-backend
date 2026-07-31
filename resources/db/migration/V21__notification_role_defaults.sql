-- =====================================================================
--  Per-role default notification subscriptions.
--
--  A template, not a live rule: the defaults for the role in a
--  provisioning request are copied into user_notification_subscriptions
--  when POST /users creates the account, inside the same transaction.
--  Changing a default never retrofits existing users — a sysadmin
--  retrofits deliberately through the bulk subscription endpoint.
--
--  role is the native user_role enum because the set of roles is
--  schema-level (see V10, which dropped 'payables'). event_type stays
--  free TEXT matching NotificationEvent.key, for the same reason it does
--  in V20: the event set evolves in code, not the schema.
--
--  The (role, event_type) primary key both de-duplicates and serves the
--  only two read patterns — all rows, or one role's rows — so no extra
--  index is needed.
-- =====================================================================
CREATE TABLE notification_role_defaults (
    role       user_role NOT NULL,
    event_type TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role, event_type)
);
