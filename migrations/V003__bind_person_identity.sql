BEGIN;
ALTER TABLE integration.receipts ADD COLUMN dispatched boolean NOT NULL DEFAULT false;
-- Legacy incomplete writes may already have reached Eden. Reconcile before attempting them again.
UPDATE integration.receipts SET dispatched=true WHERE result IS NULL AND kind IN ('PERSON','UPDATE');
ALTER TABLE integration.person_links ADD COLUMN external_uuid uuid;
UPDATE integration.person_links AS p SET external_uuid = r.external_uuid
FROM integration.receipts AS r
WHERE r.kind='PERSON' AND r.status BETWEEN 200 AND 299 AND r.result IS NOT NULL
  AND r.organization=p.organization AND r.result::jsonb->>'id'=p.person_id;
-- Do not claim a native person without a proven creation identity.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM integration.person_links WHERE external_uuid IS NULL)
       OR EXISTS (SELECT 1 FROM integration.receipts WHERE kind='PERSON' AND status BETWEEN 200 AND 299
           GROUP BY organization,result::jsonb->>'id' HAVING count(*)>1)
    THEN RAISE EXCEPTION 'Resolve legacy person identity mappings before migrating'; END IF;
END $$;
ALTER TABLE integration.person_links ALTER COLUMN external_uuid SET NOT NULL;
CREATE UNIQUE INDEX person_links_native_identity ON integration.person_links(organization,external_uuid);
INSERT INTO integration.schema_history(version) VALUES(3);
COMMIT;
