BEGIN;
CREATE SCHEMA integration;
CREATE TABLE integration.schema_history(version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE integration.receipts (
 id uuid PRIMARY KEY, organization varchar(200) NOT NULL, actor varchar(200) NOT NULL,
 kind varchar(12) NOT NULL, command text NOT NULL, external_uuid uuid NOT NULL,
 lease uuid, lease_until timestamptz, result text, status integer,
 disaster_id integer, CHECK(disaster_id IS NULL OR disaster_id>0)
);
CREATE INDEX receipts_external ON integration.receipts(organization,external_uuid);
CREATE TABLE integration.person_links (
 id varchar(240) PRIMARY KEY, organization varchar(200) NOT NULL, person_id varchar(30) NOT NULL,
 disaster_id integer, event_id varchar(30), UNIQUE(organization,person_id),
 CHECK(disaster_id IS NULL OR disaster_id>0)
);
INSERT INTO integration.schema_history(version) VALUES(1);
COMMIT;
