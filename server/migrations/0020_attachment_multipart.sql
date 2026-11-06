ALTER TABLE attachments ADD COLUMN multipart_upload_id TEXT;
CREATE TABLE attachment_upload_parts (
    attachment_id UUID NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    part_number INTEGER NOT NULL CHECK (part_number BETWEEN 1 AND 21),
    etag TEXT NOT NULL,
    PRIMARY KEY (attachment_id, part_number)
);
