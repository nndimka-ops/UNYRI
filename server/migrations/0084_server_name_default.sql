-- The product is UNYRI; 'COCO' was the old working name and still showed on
-- fresh servers. Only a name nobody changed is renamed; custom names stay.
ALTER TABLE server_policy ALTER COLUMN name SET DEFAULT 'UNYRI';
UPDATE server_policy SET name='UNYRI' WHERE name='COCO';
