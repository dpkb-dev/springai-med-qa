-- D28 MySQL bootstrap for the springai-med-qa stack.
--
-- Mounted into /docker-entrypoint-initdb.d by docker-compose.yml, this script runs once
-- on the MySQL container's first start. Flyway (V1..V3 migrations) owns the in-database
-- schema (the 16 med_message shards, med_session and med_audit_log tables); this script
-- only provisions the database and the least-privilege application account that the app's
-- ShardingSphere DataSource connects with.
--
-- All statements are idempotent so a re-run (or an attached volume already carrying data)
-- never fails the boot.

CREATE DATABASE IF NOT EXISTS med_qa
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- The application account matches the MED_MYSQL_USERNAME / MED_MYSQL_PASSWORD wired into
-- sharding/med-sharding.yaml and the docker-compose app service. '%' lets the app container
-- reach MySQL across the compose network; 'localhost' covers in-container tooling.
CREATE USER IF NOT EXISTS 'med_qa'@'%' IDENTIFIED BY 'med_qa';
CREATE USER IF NOT EXISTS 'med_qa'@'localhost' IDENTIFIED BY 'med_qa';

GRANT ALL PRIVILEGES ON med_qa.* TO 'med_qa'@'%';
GRANT ALL PRIVILEGES ON med_qa.* TO 'med_qa'@'localhost';

FLUSH PRIVILEGES;
