# iOS: five-minute delivery receipt increment

- Added POST /chats/{id}/delivered using the existing authenticated API client.
- Acknowledge only decrypted, persisted incoming messages; stop the cumulative candidate at a known decryption or identity failure.
- Retry failed acknowledgements on history refresh. Coalesce concurrent requests per chat and remember successful server watermarks within the account session.
- Check session generation before transmission and before accepting the response.
- Publish cached messages before awaiting receipt transport; transport failure does not discard history.
- No new dependencies, resources, database migrations, or Android/server changes.

Validation: reviewed the request/response against server/src/messages/handlers.rs and checked the diff. Xcode compilation is delegated to the existing GitHub Actions workflow. Physical iPhone testing is still required. Read receipts and sender-side receipt indicators are not implemented by this increment. Existing server implicit delivery-on-fetch semantics remain unchanged.

Physical acceptance: exchange Android/iOS text, inspect delivery receipts, interrupt network and restore it, switch accounts during a request, and confirm a failed decryption does not become the client's acknowledgement candidate.
