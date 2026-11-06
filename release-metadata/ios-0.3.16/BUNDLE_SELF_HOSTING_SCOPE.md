# Server scope of this iOS-only source package

This packet contains an iOS client, not the backend or a complete self-hosting
installer. Connect only to a compatible server whose operator you trust. The
operator controls their server-side access rules, storage and processing.
HTTPS protects the connection but does not establish the operator's reliability.

Choose the server through the client login/server screen or supply a default with
`UNYRI_API_BASE_URL`. Independent servers have separate accounts and histories.
Do not disable TLS checks, copy provider secrets into the app, or clear existing
accounts/Keychain to work around an address error. The client source's local-network
compatibility identifiers remain unchanged.

Actual self-hosting additionally needs backend source/configuration, HTTPS,
database/object storage, backups, and separate TURN/SFU and provider setup where
used. Those components and operational instructions are outside this iOS-only
snapshot. No public backend repository or universal push gateway is invented by
this document. APNs/PushKit and complete physical background-transfer
qualification are not supplied by publishing the client source.
