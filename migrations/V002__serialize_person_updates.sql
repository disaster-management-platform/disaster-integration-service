BEGIN;
ALTER TABLE integration.receipts ADD COLUMN target_person varchar(30);
-- An earlier uncompleted UPDATE has no recorded target. Do not guess; resolve it before migration.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM integration.receipts WHERE kind='UPDATE' AND result IS NULL)
    THEN RAISE EXCEPTION 'Resolve outstanding legacy UPDATE receipts before migrating'; END IF;
END $$;
UPDATE integration.receipts SET target_person = result::jsonb->>'id'
 WHERE kind='UPDATE' AND status BETWEEN 200 AND 299 AND result IS NOT NULL;
CREATE UNIQUE INDEX receipts_pending_target ON integration.receipts(organization,target_person)
    WHERE target_person IS NOT NULL AND result IS NULL;
CREATE UNIQUE INDEX receipts_event_scope ON integration.receipts(organization,disaster_id) WHERE kind='EVENT';
CREATE INDEX person_links_scope ON integration.person_links(organization,disaster_id,person_id);
INSERT INTO integration.schema_history(version) VALUES(2);
COMMIT;
