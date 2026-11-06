UPDATE attachments SET upload_lease_until=GREATEST(upload_lease_until,now()+interval '1 hour') WHERE status<>'attached' AND NOT cleanup_pending;
