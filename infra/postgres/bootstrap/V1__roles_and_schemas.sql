-- Runs as the Postgres admin user. Creates one schema per service, an owner role
-- (runs that service's migrations), an app role (runtime DML only), and a read-only
-- role for the Reconciler. Passwords arrive as Flyway placeholders from env vars.

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE ${flyway:database} FROM PUBLIC;

CREATE ROLE orders_owner   LOGIN PASSWORD '${orders_owner_password}';
CREATE ROLE orders_app     LOGIN PASSWORD '${orders_app_password}';
CREATE ROLE payments_owner LOGIN PASSWORD '${payments_owner_password}';
CREATE ROLE payments_app   LOGIN PASSWORD '${payments_app_password}';
CREATE ROLE reconciler_ro  LOGIN PASSWORD '${reconciler_password}';

GRANT CONNECT ON DATABASE ${flyway:database}
  TO orders_owner, orders_app, payments_owner, payments_app, reconciler_ro;

CREATE SCHEMA orders   AUTHORIZATION orders_owner;
CREATE SCHEMA payments AUTHORIZATION payments_owner;

-- Each app role reaches only its own schema.
GRANT USAGE ON SCHEMA orders   TO orders_app;
GRANT USAGE ON SCHEMA payments TO payments_app;
GRANT USAGE ON SCHEMA orders, payments TO reconciler_ro;

-- Privileges on objects the owners create later, in their own migrations.
ALTER DEFAULT PRIVILEGES FOR ROLE orders_owner IN SCHEMA orders
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO orders_app;
ALTER DEFAULT PRIVILEGES FOR ROLE orders_owner IN SCHEMA orders
  GRANT USAGE, SELECT ON SEQUENCES TO orders_app;
ALTER DEFAULT PRIVILEGES FOR ROLE orders_owner IN SCHEMA orders
  GRANT SELECT ON TABLES TO reconciler_ro;

ALTER DEFAULT PRIVILEGES FOR ROLE payments_owner IN SCHEMA payments
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO payments_app;
ALTER DEFAULT PRIVILEGES FOR ROLE payments_owner IN SCHEMA payments
  GRANT USAGE, SELECT ON SEQUENCES TO payments_app;
ALTER DEFAULT PRIVILEGES FOR ROLE payments_owner IN SCHEMA payments
  GRANT SELECT ON TABLES TO reconciler_ro;

ALTER ROLE orders_owner   SET search_path = orders;
ALTER ROLE orders_app     SET search_path = orders;
ALTER ROLE payments_owner SET search_path = payments;
ALTER ROLE payments_app   SET search_path = payments;

-- The Reconciler must never hold locks that slow the sale down.
ALTER ROLE reconciler_ro SET default_transaction_read_only = on;
ALTER ROLE reconciler_ro SET statement_timeout = '30s';
