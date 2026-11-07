import Foundation

/// REST client for the storage-authorization side of attachments (mirrors
/// Android's `AttachmentsApi`): upload-intent/complete/download-URL only. The
/// actual encrypted bytes never pass through this client or the UNYRI backend —
/// they go direct-to-storage via the presigned PUT/GET URLs (see
/// `AttachmentManager`).
struct AttachmentsAPI {
    let client: APIClient

    func multipart(accessToken: String, attachmentId: String) async throws -> MultipartUpload {
        try await client.post("/attachments/\(attachmentId)/multipart", accessToken: accessToken,
            body: [String: String](), as: MultipartUpload.self)
    }
    func acknowledgePart(accessToken: String, attachmentId: String, number: Int, etag: String) async throws {
        let _: [String: Bool] = try await client.post("/attachments/\(attachmentId)/parts/\(number)", accessToken: accessToken,
            body: MultipartReceipt(etag: etag), as: [String: Bool].self)
    }

    /// `POST /attachments` — idempotent on `client_attachment_id` (scoped to
    /// the uploading device); a retried request returns the SAME
    /// attachment/object_key/URL rather than creating an orphan object.
    func create(
        accessToken: String,
        clientAttachmentId: String,
        encryptedSize: Int64
    ) async throws -> CreateAttachmentResponse {
        try await client.post(
            "/attachments",
            accessToken: accessToken,
            body: CreateAttachmentRequest(
                clientAttachmentId: clientAttachmentId,
                encryptedSize: encryptedSize
            ),
            as: CreateAttachmentResponse.self
        )
    }

    /// `POST /attachments/{id}/complete` — verifies the object landed in
    /// storage with the declared size before it becomes message-referencable.
    /// Idempotent; already-uploaded is a no-op success.
    func complete(accessToken: String, attachmentId: String) async throws {
        try await client.postNoContent("/attachments/\(attachmentId)/complete", bearerToken: accessToken)
    }

    /// `GET /attachments/{id}/download` — returns a presigned GET URL after
    /// the authorization state machine (uploader pre-attach, chat members
    /// post-attach).
    func download(accessToken: String, attachmentId: String) async throws -> DownloadAttachmentResponse {
        try await client.get(
            "/attachments/\(attachmentId)/download",
            accessToken: accessToken,
            as: DownloadAttachmentResponse.self
        )
    }
}
