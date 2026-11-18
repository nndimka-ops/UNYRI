#!/bin/sh
set -eu
umask 077
# Keep credentials and mc's configuration in tmpfs; never enable shell tracing.
if ! {
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" &&
    mc mb --ignore-existing local/messenger-attachments &&
    mc anonymous set none local/messenger-attachments &&
    sed 's/__COCO_BUCKET__/messenger-attachments/g' /policy.template.json > /tmp/uploads.json &&
    mc admin policy create local unyri-client-uploads /tmp/uploads.json &&
    mc admin user add local "$S3_UPLOAD_ACCESS_KEY_ID" "$S3_UPLOAD_SECRET_ACCESS_KEY" &&
    mc admin policy attach local unyri-client-uploads --user "$S3_UPLOAD_ACCESS_KEY_ID"
} >/dev/null 2>&1; then
    echo 'Storage initialization failed; check MinIO health/configuration. No credentials were logged.' >&2
    exit 1
fi
echo 'Private bucket and separate write-only upload principal are ready.'
