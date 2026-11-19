BEGIN;
ALTER TABLE integration.receipts ADD COLUMN target_resource varchar(64);
CREATE UNIQUE INDEX receipts_pending_resource ON integration.receipts(organization,target_resource)
    WHERE target_resource IS NOT NULL AND result IS NULL;
INSERT INTO integration.schema_history(version) VALUES(4);
COMMIT;
