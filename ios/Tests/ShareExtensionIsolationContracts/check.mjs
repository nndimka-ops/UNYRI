import {readFileSync, existsSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const read = path => readFileSync(resolve(root, path), 'utf8');
let checks = 0;
const check = (condition, reason) => { assert(condition, reason); checks++; };
const project = read('ios/UNYRI.xcodeproj/project.pbxproj');
// Parse the actual OpenStep plist, including all arrays/dictionaries. Textual
// membership checks alone would miss a lost comma/semicolon or an orphan ref.
function parseProject(source) {
    const tokens = source.match(/\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\])*"|[{}()=;,]|[^\s{}()=;,]+/g)
        .filter(token => !token.startsWith('/*') && !token.startsWith('//'));
    let position = 0;
    const take = expected => { const token = tokens[position++]; if (expected) assert.equal(token, expected); return token; };
    const atom = token => token.startsWith('"') ? token.slice(1, -1) : token;
    function value() {
        if (tokens[position] === '{') {
            take('{'); const result = Object.create(null);
            while (tokens[position] !== '}') {
                const key = atom(take()); take('=');
                assert(!Object.hasOwn(result, key), 'duplicate plist key: ' + key);
                result[key] = value(); take(';');
            }
            take('}'); return result;
        }
        if (tokens[position] === '(') {
            take('('); const result = [];
            while (tokens[position] !== ')') { result.push(value()); if (tokens[position] !== ')') take(','); }
            take(')'); return result;
        }
        return atom(take());
    }
    const result = value(); assert.equal(position, tokens.length, 'all project tokens parsed'); return result;
}
const projectPlist = parseProject(project);
check(projectPlist.objects[projectPlist.rootObject]?.isa === 'PBXProject', 'OpenStep project grammar and root object');
for (const object of Object.values(projectPlist.objects)) {
    for (const key of ['fileRef','productReference']) if (object[key]) check(!!projectPlist.objects[object[key]], 'resolved ' + key);
    if (Array.isArray(object.buildPhases)) check(object.buildPhases.every(id => !!projectPlist.objects[id]), 'resolved target build phases');
    if (object.isa?.endsWith('BuildPhase') && Array.isArray(object.files)) check(object.files.every(id => projectPlist.objects[id]?.isa === 'PBXBuildFile'), 'resolved phase build files');
}
const definitions = [...project.matchAll(/^\s*([A-F0-9]{23,24})\s*(?:\/\*.*?\*\/\s*)?=\s*\{\s*isa\s*=/gm)].map(match => match[1]);
check(new Set(definitions).size === definitions.length, 'unique Xcode object definitions');
function object(id) {
    const start = project.indexOf(id + ' = {') >= 0 ? project.indexOf(id + ' = {') : project.search(new RegExp(id + ' /\\*[^\\n]*?\\*/ = \\{'));
    assert(start >= 0, id);
    let at = project.indexOf('{', start), depth = 1, end = at + 1;
    while (depth) { if (project[end] === '{') depth++; if (project[end] === '}') depth--; end++; }
    return project.slice(at, end);
}
const ids = value => [...value.matchAll(/\b[A-F0-9]{24}\b/g)].map(match => match[0]);
const files = phase => ids(object(phase).match(/files = \(([\s\S]*?)\)/)[1]).map(build => {
    const ref = object(build).match(/fileRef = ([A-F0-9]{24})/)[1];
    return object(ref).match(/path = "?([^;"\n]+)"?;/)[1];
});
const sources = files('E14000000000000000000001');
const expected = ['UNYRIShare/ShareViewController.swift', 'UNYRIShare/LegacyShareViewController.swift', 'UNYRIShare/ShareComposerView.swift', 'UNYRIShare/ShareStrings.swift',
    ...['UnyriShareStore','ShareContainer','ShareJob','ShareRoutePolicy','ShareTargetSnapshot','ShareSignal','MessageTextPolicy','ShareItemClassification'].map(name => `Shared/${name}.swift`)];
check([...sources].sort().join('|') === expected.sort().join('|'), 'extension source allowlist');
for (const path of sources) check(existsSync(resolve(root, 'ios', path)), 'source exists: ' + path);
const extensionTarget = object('E13000000000000000000001');
const phases = ids(extensionTarget.match(/buildPhases = \(([\s\S]*?)\)/)[1]);
const frameworks = phases.filter(phase => object(phase).includes('isa = PBXFrameworksBuildPhase')).flatMap(files);
for (const name of ['SwiftUI','LocalAuthentication','AVFoundation','ImageIO']) check(frameworks.some(path => path.endsWith('/' + name + '.framework')), 'extension framework: ' + name);
check(!frameworks.some(path => /LibSignal|WebRTC|SQLite|LiveKit/i.test(path)), 'no private/runtime framework in extension');
const allExtension = sources.map(path => read('ios/' + path)).join('\n');
for (const token of ['import LibSignalClient', 'import WebRTC', 'import SQLite3', 'SessionStore', 'SignalStateStore', 'URLSession', 'INSendMessageIntent', 'NSFilePresenter', 'flock(']) {
    check(!allExtension.includes(token), 'extension isolation: ' + token);
}
for (const shared of ['ShareContainer','ShareJob','ShareRoutePolicy','ShareTargetSnapshot','ShareSignal','MessageTextPolicy','ShareItemClassification']) {
    check(project.includes(`${shared}.swift in Sources (app)`) || shared === 'MessageTextPolicy', 'app membership: ' + shared);
}
const appSources = files('DD0000000000000000000001');
for (const path of ['ShareContainer','ShareJob','ShareRoutePolicy','ShareTargetSnapshot','ShareSignal','MessageTextPolicy','ShareItemClassification'].map(name => `Shared/${name}.swift`)) {
    check(appSources.includes(path) && sources.includes(path), 'actual membership in both targets: ' + path);
}
const strings = read('ios/UNYRIShare/ShareStrings.swift');
const catalog = JSON.parse(read('ios/UNYRI/Localizable.xcstrings')).strings;
const triples = [...strings.matchAll(/^\s*"([^"\n]+)": \("([^"\n]+)", "([^"\n]+)"\),?$/gm)];
check(triples.length >= 40, 'extension translation coverage');
for (const [, en, ru, uk] of triples) {
    for (const [language, expected] of [['en',en],['ru',ru],['uk',uk]]) {
        check(catalog[en]?.localizations?.[language]?.stringUnit?.value === expected, 'catalog translation: ' + language + '/' + en);
    }
}
const composer = read('ios/UNYRIShare/ShareComposerView.swift');
const writer = read('ios/UNYRI/Share/ShareTargetSnapshotWriter.swift');
const processor = read('ios/UNYRI/Share/ShareJobProcessor.swift');
const app = read('ios/UNYRI/UNYRIApp.swift');
const chats = read('ios/UNYRI/Chats/ChatsRepository.swift');
const outbox = read('ios/UNYRI/Messages/MessageOutbox.swift');
check(composer.includes('LAContext()') && composer.includes('.deviceOwnerAuthentication'), 'native app lock');
check(composer.indexOf('guard await unlock()') < composer.indexOf('index = loaded'), 'no account names before authentication');
check(composer.includes('CGImageSourceThumbnailMaxPixelSize: 160') && composer.includes('generator.maximumSize'), 'bounded media previews');
check(composer.includes('clonefile(sourcePath, destinationPath, UInt32(CLONE_NOFOLLOW))') && composer.includes('cancellation: cancellation, progress:'), 'clone-only and cancellable streaming fallback');
check(!composer.includes('Data(contentsOf:'), 'no whole-file loads in picker');
check(composer.includes('ShareItemClassification.select(representations, livePhoto: live)'), 'native provider uses tested classification');
check(composer.includes('type.conforms(to: .livePhoto)') && composer.includes('category = .image') && composer.includes('category = .video'), 'native UTType adapter');
check(composer.includes('ShareItemClassification.initialSendAs(kind: kind)') && !composer.includes('choice == "file" ? .file'), 'remembered document mode cannot change Photos media defaults');
check(composer.includes('progress.observe(\\.fractionCompleted') && composer.includes('Self.observeProvider(progress)'), 'event-driven provider download progress');
check(composer.includes('nonisolated private static func observeProvider') && composer.includes('let complete: @Sendable'), 'provider callbacks do not inherit MainActor');
check(composer.includes('providerObservation?.invalidate()') && composer.includes('if cancel { providerProgress?.cancel() }'), 'Close retires both observer and provider transfer');
for (const api of ['loadDataRepresentation(', 'loadObject(', 'PHImageManager']) check(!composer.includes(api), 'file-backed media only: ' + api);
check(processor.includes('allowUnreadableAsFile: false'), 'unreadable Photos media never silently becomes a document');
const messages = read('ios/UNYRI/Messages/MessagesRepository.swift');
const shareAdoption = processor.slice(processor.indexOf('}, adopt: { item, source in'), processor.indexOf('}, enqueue: { job, item, url in'));
check(shareAdoption.indexOf('try await messages.waitForDraftSourceRecovery()') >= 0 &&
    shareAdoption.indexOf('try await messages.waitForDraftSourceRecovery()') < shareAdoption.indexOf('let destination ='),
    'share adoption waits for composer launch cleanup before examining or copying private sources');
const sourceRecoveryWait = messages.slice(messages.indexOf('func waitForDraftSourceRecovery()'), messages.indexOf('func stageDraftFile('));
check(sourceRecoveryWait.includes('try await draftSourceRecovery.wait()') &&
    sourceRecoveryWait.match(/try requireSession\(\)/g)?.length === 2 &&
    sourceRecoveryWait.match(/try Task.checkCancellation\(\)/g)?.length === 2,
    'draft recovery wait rechecks session and cancellation after the sweep');
check(app.includes('ShareJobProcessor.native(auth: auth, chats: chats, outbox: outbox, messages: messagesRepository,'),
    'share processor and composer use the same repository recovery fence');
const messageStore = read('ios/UNYRI/Data/MessageStore.swift');
const mediaPreparation = outbox.slice(outbox.indexOf('private func prepareMedia('), outbox.indexOf('// MARK: - Enqueue / retry'));
check(mediaPreparation.includes('try loadMediaJob(raw, chatId: message.chatId, id: message.clientMessageId)') &&
    outbox.includes('let source = store.rebasedMediaSource(job.url)') && messageStore.includes('func rebasedMediaSource(_ url: URL)'),
    'actual outbox preparation rebases legacy persisted sources into its current account');
const replayContracts = read('ios/Tests/ShareOutboxRelocationContracts/run.py');
const replayFixture = read('ios/Tests/ShareOutboxRelocationContracts/main.swift');
const attachmentStore = read('ios/UNYRI/Data/AttachmentStore.swift');
const cacheMigration = attachmentStore.slice(attachmentStore.indexOf('private func relocateCachePathsAfterContainerMove()'), attachmentStore.indexOf('private func relocateDraftReferencesAfterContainerMove('));
const draftMigration = attachmentStore.slice(attachmentStore.indexOf('private func relocateDraftReferencesAfterContainerMove('), attachmentStore.indexOf('func upsert('));
const draftGate = attachmentStore.slice(attachmentStore.indexOf('private final class DraftReferenceMigration:'), attachmentStore.indexOf('convenience init(databaseURL:'));
check(cacheMigration.includes("key='cache_root_v1'") && !cacheMigration.includes('local_roots_v2') &&
    draftMigration.includes("key='local_roots_v2'") && !draftMigration.includes('cache_root_v1'),
    'cache v1 and draft v2 retain independent atomic markers');
check(draftGate.includes('Task.detached(priority: .utility)') &&
    draftGate.includes('let worker = try AttachmentStore(databaseURL: database, readOnly: false, migrate: false, metadataWriter: writer, busyTimeout: 0)') &&
    draftGate.includes('try worker.relocateDraftReferencesAfterContainerMove(hooks: hooks)') &&
    !attachmentStore.includes('@MainActor static func open('),
    'only the lazy draft gate starts a background connection, never async store opening');
check(draftGate.includes('onCancel: { self.cancelWaiter(id) }') && draftGate.includes('waiters.removeValue(forKey: id)') &&
    draftGate.includes('let running = retire()') && draftGate.includes('await running?.value') &&
    draftMigration.indexOf('try hooks.beforeCommit?()') < draftMigration.indexOf('try execute("BEGIN IMMEDIATE;")') && draftMigration.includes('try execute("COMMIT;")'),
    'individual waits cancel promptly; account erasure drains and rolls back its worker');
check(draftMigration.includes("local_path LIKE '%/draft-media/%' ORDER BY rowid LIMIT 256") &&
    draftMigration.includes('guard previous != accountRoot else { return }') &&
    attachmentStore.includes('if migrate { try relocateCachePathsAfterContainerMove() }'),
    'v1 stays synchronous and draft-only bounded v2 skips completed roots');
const publication = draftMigration.slice(draftMigration.indexOf('try metadataWriter.publish {'));
check(draftMigration.includes('CREATE TEMP TABLE draft_reference_repairs') && draftMigration.includes('PRAGMA temp_store=FILE') &&
    draftMigration.indexOf('relocation.relocatedPath(for:') < draftMigration.indexOf('try metadataWriter.publish {') &&
    !publication.includes('relocatedPath(') && !publication.includes('hooks.') &&
    publication.includes('r.attachment_id=attachments.attachment_id AND r.original=attachments.local_path'),
    'filesystem inspection stages in TEMP before the short atomic CAS publication');
const ingestor = read('ios/UNYRI/Messages/MessageIngestor.swift');
const persistDecrypted = ingestor.slice(ingestor.indexOf('private func persistDecryptedMessage('), ingestor.indexOf('private func persistAttachment('));
check(persistDecrypted.indexOf('try store.upsert(message, journalAttachment: true)') < persistDecrypted.indexOf('try await persistAttachment(') &&
    messageStore.includes('if journalAttachment, message.kind == "content", message.content.attachment != nil') &&
    ingestor.includes('try await attachmentStore.upsertIncoming(record, sequence: dto.sequence)'),
    'consumed Signal plaintext and metadata-repair journal commit before background metadata persistence');
check(attachmentStore.includes('catch StoreError.busy where retries < 8') &&
    attachmentStore.includes('self.incomingWriter!.upsert(attachment, preservingExisting: true, sourceSequence: sequence, requiresJournal: true)') &&
    attachmentStore.includes('metadataWriter.retire()') && attachmentStore.includes('await metadataWriter.drain { self.incomingWriter = nil }'),
    'live metadata writes retry contention off-main, preserve local originals and drain on retirement');
const recoveryOwner = messageStore.slice(messageStore.indexOf('final class DraftSourceRecovery:'), messageStore.indexOf('/// Raw, self-contained preview'));
check(recoveryOwner.includes('onCancel: { self.detach(id) }') && recoveryOwner.includes('waiters.removeValue(forKey: id)') &&
    recoveryOwner.includes('func cancel() { task.cancel() }') &&
    (messages.match(/try await draftSourceRecovery\.wait\(\)/g) ?? []).length === 3 &&
    (messages.match(/await draftSourceRecovery\.result/g) ?? []).length === 1,
    'all share/staging/copy callers cancel their waits independently; only retirement drains the shared task');
const concurrencyRunner = read('ios/Tests/ShareRecoveryConcurrencyContracts/run.py');
const concurrencyFixture = read('ios/Tests/ShareRecoveryConcurrencyContracts/main.swift');
check(concurrencyRunner.includes("'    private func ingestOne('") && concurrencyRunner.includes("'    func copyRecentFiles('") &&
    concurrencyFixture.includes('plaintextSurvivesMetadataFailure') && concurrencyFixture.includes('waitingConsumerCount < 4') &&
    concurrencyFixture.includes('try await processor.process()') &&
    read('.github/workflows/ios-build.yml').includes('python3 ios/Tests/ShareRecoveryConcurrencyContracts/run.py'),
    'CI contracts execute production ingestion and indirect recovery callers through contention and cancellation');
const incomingInsert = attachmentStore.slice(attachmentStore.indexOf('func upsert('), attachmentStore.indexOf('func upsertIncoming('));
check(incomingInsert.includes('WHERE NOT EXISTS (SELECT 1 FROM deleted_chats WHERE chat_id=:source_chat)') &&
    incomingInsert.includes('AND NOT EXISTS (SELECT 1 FROM deleted_attachment_messages WHERE client_message_id=:source_message)') &&
    !incomingInsert.includes('guard !deleted else'),
    'chat/message tombstones are predicates of the insertion, never a separate stale check');
const metadataPurges = attachmentStore.slice(attachmentStore.indexOf('func deleteRecords('), attachmentStore.indexOf('func cachedPathsAsync('));
check(metadataPurges.includes('invalidateIncomingWrites(messageIDs: ids)') && metadataPurges.includes('invalidateIncomingWrites(chatId: chatId)') &&
    metadataPurges.includes('try metadataWriter.publish {') &&
    metadataPurges.includes('INSERT INTO deleted_attachment_messages(client_message_id,chat_id,sequence)') &&
    attachmentStore.includes('defer { metadataWriter.finish(pending); scheduleFenceReclamation(message: attachment.clientMessageId) }') && attachmentStore.includes('try pending.check()'),
    'purges invalidate active retry tickets, serialize final deletion and persist message fences');
const attachmentManager = read('ios/UNYRI/Attachments/AttachmentManager.swift');
check(attachmentManager.includes('store.invalidateIncomingWrites(messageIDs: messageIDs)') &&
    attachmentManager.includes('guard !initial.isEmpty else { try store.deleteRecords([clientMessageId]); return [] }') &&
    attachmentManager.includes('store.invalidateIncomingWrites(chatId: chatId)') &&
    attachmentManager.includes('try removeCachedFiles(of: initial + records)'),
    'manager purges fence absent metadata too and retain initial file paths during late-row cleanup');
const finishPersistence = ingestor.slice(ingestor.indexOf('private func finishAttachmentPersistence('), ingestor.indexOf('private func persistAttachment('));
check((finishPersistence.match(/store\.attachmentMessageIsVisible\(/g) ?? []).length === 2 &&
    finishPersistence.indexOf('try attachmentStore.deleteRecords([dto.clientMessageId], chatId: dto.chatId)') < finishPersistence.indexOf('try store.finishHistoryAttachmentJob(') &&
    messageStore.includes('let cutoff = max(try personalCutoff(chatId), serverCutoff)') &&
    finishPersistence.includes('catch AttachmentStore.StoreError.deleted'),
    'ingestion revalidates visibility and both clear cutoffs and removes late rows before journal consumption');
check(concurrencyRunner.includes("'    func clearPersonalChat('") && concurrencyRunner.includes("'    func purgePersonalChat('") &&
    concurrencyFixture.includes('deletionDuringRetry') && concurrencyFixture.includes('revalidateAfterPersistence') &&
    concurrencyFixture.includes('try await deletion.purgeDeletedChat(chat)') && concurrencyFixture.includes('try await deletion.clearPersonalChat(chat, through: dto.sequence)'),
    'deletion-during-retry contracts extract real repository and manager purge callers');
check(metadataPurges.includes('if let chatId, let throughSequence') &&
    metadataPurges.includes('MAX(through_sequence,excluded.through_sequence)') &&
    metadataPurges.includes('pending != nil || journal != nil || old != nil') &&
    metadataPurges.includes('DELETE FROM deleted_attachment_messages WHERE chat_id=?;') &&
    attachmentManager.includes('try store.deleteRecords(messageIDs, chatId: chatId, throughSequence: throughSequence)'),
    'history clears compact to monotonic cutoffs; individual fences require an actual metadata owner and chat deletion supersedes them');
const reclaim = attachmentStore.slice(attachmentStore.indexOf('private func reclaimFenceBatch('), attachmentStore.indexOf('// MARK: - Writes'));
check(reclaim.includes('LIMIT 256;') && reclaim.includes('temp.active_metadata_sources') &&
    reclaim.includes('temp.attachment_journal_sources') && reclaim.includes('metadataWriter.source(id) != nil') &&
    attachmentStore.includes('func attachmentJournalConsumed(_ message: String)') && ingestor.includes('attachmentStore.startFenceReclamation()') &&
    outbox.includes('attachmentManager?.attachmentJournalConsumed(id)'),
    'finite background reclamation retains active writes/journals and is triggered at launch and source consumption');
check(incomingInsert.includes('requiresJournal') && incomingInsert.includes('m.hidden_locally=0 AND m.delete_sequence=0') &&
    incomingInsert.includes('COALESCE(m.sequence,:source_sequence)') && incomingInsert.includes("name='personal_clear'") &&
    attachmentManager.includes('try store.upsert(record, requiresJournal: true)') &&
    read('ios/UNYRI/Support/HistoryAttachmentRestorer.swift').includes('requiresJournal: true'),
    'all production metadata producers validate live journal owners so reclaimed fences cannot admit stale replay');
check(concurrencyFixture.includes('boundedFences') && concurrencyFixture.includes('legacyFenceLaunchSweep') &&
    concurrencyFixture.includes('Consumed journal left a permanent message fence') &&
    read('ios/Tests/ShareRecoveryConcurrencyContracts/check_sql.mjs').includes("length:10000"),
    'contracts cover repeated clears, consumed-source reclamation, legacy launch sweep, ID reuse and resurrection after compaction');
const servicesSource = app.slice(app.indexOf('private final class AccountServices:'));
const servicesStartup = servicesSource.slice(servicesSource.indexOf('init(auth: AuthRepository)'), servicesSource.indexOf('func installBackgroundPushHandler()'));
check(servicesStartup.includes('let attachmentStore = try AttachmentStore(') &&
    !app.includes('waitUntilReady') && !app.includes('initializationTask') && !app.includes('isInitializing') &&
    !app.includes('private func initialize(auth:') && servicesStartup.includes('let callKit = CallKitCoordinator('),
    'AccountServices and call recovery construct synchronously without a global readiness shell');
check(app.includes('await attachments?.cancelAndDrainDraftReferences()') &&
    app.indexOf('await attachments?.cancelAndDrainDraftReferences()') < app.indexOf('await accountMessages?.finishSessionDraftWrites()'),
    'only retirement drains the draft worker before account file erasure');
const qaHost = read('scripts/qa/generate_actual_chat_return_host.py');
check(!qaHost.includes('waitUntilReady') && !qaHost.includes('fixtureReady') &&
    !qaHost.includes('@StateObject private var services: AccountServices') &&
    app.includes('auth.sessionGeneration == services.sessionGeneration && incomingFile.hasPendingImport'),
    'QA, deep links and import presentation retain synchronous service consumers');
const openingContracts = read('ios/Tests/AttachmentCacheRelocationContracts/main.swift');
check(openingContracts.includes('Synchronous launch does not start or await the draft scan') &&
    openingContracts.includes('Only normalized draft readers and cleanup wait for COMMIT') &&
    openingContracts.includes('Cancelled scan rolls its marker back') && openingContracts.includes('noScan.paths.isEmpty') &&
    openingContracts.includes('noDrafts.paths.isEmpty') && openingContracts.includes("One account cannot hold another account's draft gate"),
    'real-store contracts cover narrow readiness, account isolation, cancellation, rollback, v1 skip and no rescan');
check(mediaPreparation.includes('try await manager.waitForDraftReferences(for: [job.url])') &&
    mediaPreparation.indexOf('if job.metadata == nil {') < mediaPreparation.indexOf('try await manager.waitForDraftReferences(for: [job.url])') &&
    outbox.includes('private func releaseMediaSource(_ id: String) async') &&
    outbox.includes('defer { self.sourceCleanups[id] = nil }') && outbox.includes('scheduleSourceCleanup(message.clientMessageId)') && !outbox.includes('await releaseMediaSource(message.clientMessageId)'),
    'replay/cleanup gate draft sources without stalling text catch-up');
check(messageStore.includes('let attachmentPaths = try await attachmentReferences()') &&
    attachmentStore.includes('try await migration.wait()') &&
    attachmentStore.includes('!draftMigration.isReady { throw StoreError.draftReferencesNotReady }'),
    'launch source recovery waits for normalized ownership and synchronous cleanup fails closed');
check(processor.includes('messages.discardUnownedDraftSource(destination)') &&
    messages.includes('try await attachmentManager.waitForDraftReferences(for: [url])'),
    'abandoned share copies and composer source cleanup use the account-scoped gate');
check(!read('ios/UNYRI/Attachments/AttachmentManager.swift').includes('try await waitForDraftReferences(for: initial.') &&
    messages.includes('for url in urls { discardUnownedDraftSource(url) }'),
    'push deletion updates rows without waiting for deferred draft-file removal');
check(attachmentStore.includes('attachment.localPath = normalizedDraftPath(path)') &&
    attachmentStore.includes('try bindText(statement, 2, normalizedDraftPath(path))'),
    'late attachment writes and indexed ownership lookups normalize draft source identities');
check(replayContracts.includes("'    private func releaseMediaSource('") && replayContracts.includes("'    func resumeUpload('") &&
    replayContracts.includes("'ios/UNYRI/Data/AttachmentStore.swift'") && replayFixture.includes('replayAndCleanVoice') &&
    replayFixture.includes('Cleanup deleted the attachment-owned recording after relocation'),
    'prepared voice contract uses real AttachmentStore, resumeUpload and outbox cleanup through relocation');
check(replayFixture.includes('Source cleanup/recovery bypassed the draft gate') &&
    replayFixture.includes('messages.startDraftSourceRecovery(attachmentReferences: attachments.draftSourceReferences())'),
    'held-worker relocation contract executes real cleanup and launch source recovery');
check(read('ios/UNYRI/Support/HistoryAttachmentRestorer.swift').includes('old?.localPath.map { attachments.normalizedDraftPath($0) }') &&
    replayFixture.includes('Synchronous restore journal dropped a relocated voice source') &&
    replayContracts.includes("'ios/UNYRI/Support/HistoryAttachmentRestorer.swift'"),
    'synchronous journal replay repairs only its known row before deciding local ownership');
check(replayContracts.includes("'    private func prepareMedia('") && replayContracts.includes("'    private func loadMediaJob('") &&
    replayContracts.includes("'ios/UNYRI/Data/MessageStore.swift'") && replayFixture.includes('try fm.moveItem(at: moved, to: again)'),
    'relocation contract executes real preparation and SQLite after two physical moves');
check(read('.github/workflows/ios-build.yml').includes('python3 ios/Tests/ShareOutboxRelocationContracts/run.py'), 'outbox relocation runs in CI');
const container = read('ios/Shared/ShareContainer.swift');
check(writer.includes('!authority.revokedAccounts.contains(otherKey)') &&
    writer.includes('ShareContainer.publishSnapshot(authority: authority, root: root, accountKeys: accounts.map(\\.key))'),
    'revoked accounts are removed from writer inputs and queued publications are fenced');
check(container.indexOf('try revokeSnapshotAccounts(revoked, root: root)') < container.indexOf('try UnyriShareStore.removeIfPresent(targetRoot.appendingPathComponent(accountKey))') &&
    container.includes('SharePrivacy/snapshot-authority.json') && container.includes('snapshotAuthority(root: root) == authority'),
    'persistent revocation precedes erasure on the shared disk executor');
check(read('ios/UNYRI/Auth/AuthRepository.swift').includes('restoreSnapshotAccount(scope.accountKey') &&
    writer.includes('ShareContainer.snapshotAuthorityChanged'), 'only active authenticated scope restores its publication right');
check(composer.includes('let coordinator = NSFileCoordinator()') && composer.includes('cancellation.onCancel { coordinator.cancel() }') &&
    composer.includes('coordinator.coordinate(readingItemAt: url') && !composer.includes('NSFileCoordinator().coordinate('),
    'one retained coordinator is cancellable for file representations and URL-only providers');
check(composer.includes('progressCancellation = cancellation.onCancel { progress.cancel() }') &&
    composer.includes('onCancel: { cancellation.cancel() }') && composer.includes('removeHandler(coordinationCancellation)'),
    'task, Close and account selection retire coordinator and provider progress together');
const urlProvider = composer.slice(composer.indexOf('if selection.usesFileURL {'), composer.indexOf('// Photos/iCloud materializes'));
const providerBridge = composer.slice(composer.indexOf('nonisolated private static func loadURLItem('), composer.indexOf('nonisolated private static func observeProvider('));
check(urlProvider.includes('Self.loadURLItem(provider, cancellation: cancellation, completion: complete)') &&
    providerBridge.indexOf('DispatchQueue.global(qos: .userInitiated).async') >= 0 &&
    providerBridge.indexOf('DispatchQueue.global(qos: .userInitiated).async') < providerBridge.indexOf('provider.loadItem(') &&
    providerBridge.slice(providerBridge.indexOf('provider.loadItem(')).includes('completion(value as? URL, error)') &&
    !/DispatchQueue|Task\s*\{/.test(providerBridge.slice(providerBridge.indexOf('provider.loadItem('))),
    'URL provider request starts off-main and its callback copies inline without an asynchronous lifetime escape');
const providerContracts = read('ios/Tests/ShareProviderLifetimeContracts/main.swift');
check(providerContracts.includes('provider.insideCallback && !provider.returned') &&
    providerContracts.includes('removeItem(at: source)') && providerContracts.includes('unopened.calls == 0') &&
    read('ios/Tests/ShareProviderLifetimeContracts/run.py').includes("'    nonisolated private static func loadURLItem('") &&
    read('.github/workflows/ios-build.yml').includes('python3 ios/Tests/ShareProviderLifetimeContracts/run.py'),
    'CI executes production provider bridge with immediate temp deletion, callback-stack and cancellation contracts');
check(processor.indexOf('ShareJobStore.expireSources(job, in: directory, now: now)') < processor.indexOf('if job.accountKey != dependencies.accountKey'),
    'inactive committed plaintext expires before the account admission gate');
check(messageStore.includes('folder.hasPrefix("share-") ? String(folder.dropFirst(6)) : folder') &&
    messageStore.includes('guard UUID(uuidString: identifier) != nil else { return nil }'),
    'moved share UUID directories participate in the same bounded ownership recovery as composer sources');
const workspaceContracts = read('ios/Tests/WorkspaceContracts/main.swift');
check(workspaceContracts.includes('sharedBeforeAdmission') && workspaceContracts.includes('oldContainer(sharedUpload)') &&
    workspaceContracts.includes('Unowned share cleanup did not rebase to the current account directory'),
    'workspace contracts cover abandoned, moved owned and released share sources');
check(outbox.includes('case "image": return try await manager.prepareAndUploadImage') && read('ios/UNYRI/Attachments/AttachmentManager.swift').includes('ImageProcessor.processImage(sourceURL: fileURL'), 'same image conversion as in-app gallery');
const walkthrough = read('ios/UITests/UNYRIWalkthroughUITests.swift');
check(walkthrough.includes('case "sharephotos": try photosShareProbe()') && walkthrough.includes('bundleIdentifier: "com.apple.mobileslideshow"'), 'walkthrough uses the Photos app');
check(walkthrough.includes('imageTiles.count >= beforeImages + 2 && videoTiles.count >= beforeVideos + 2'), 'mixed share walkthrough asserts media kinds');
check(read('docs/SHARE_PHOTOS_WALKTHROUGH.md').includes('iCloud-only long video'), 'physical-device cloud download checks documented');
check(composer.includes('.lineLimit(1...4)') && composer.includes('.captionValid') && composer.includes('.accessibilityLabel'), 'caption and accessibility');
check(composer.includes('glassEffect') && composer.includes('accessibilityReduceTransparency') && composer.includes('dynamicTypeSize'), 'glass and Dynamic Type');
check(app.includes('CFNotificationCenterGetDarwinNotifyCenter') && app.includes('shareJobProcessor?.request()'), 'Darwin delivery observer');
check(app.includes('shareProtectedDataAvailable()') && app.includes('retireShareWork()'), 'unlock and retirement hooks');
check(processor.includes('beginBackgroundTask') && processor.includes('endBackgroundTask'), 'bounded import background assertion');
check(processor.includes('outbox.enqueueMedia(') && processor.includes('outbox.enqueueVisualMedia('), 'existing send paths');
check(processor.includes('message(byClientMessageId: item.id)') && processor.includes('row.chatId == job.target.chatId'), 'durable id and target binding');
check(processor.includes('admission: {') && outbox.match(/guard admission\?\(\) \?\? true/g)?.length === 2, 'block/delete guard after async visual probing');
for (const input of ['$cacheRevision','$archivedChatIDs','$blockedAccountsRevision','$shareAvatarRevision','UserDefaults.didChangeNotification']) check(writer.includes(input), 'snapshot trigger: ' + input);
check(writer.includes('gate.differs(hash)') && writer.includes('2_000_000_000') && writer.includes('writtenAt: 0'), 'debounce and timestamp-independent hash');
check(writer.includes('content.sha256') && writer.includes('existing.accounts == accounts'), 'hash gate survives launch and account switches');
check(writer.indexOf('gate.differs(hash)') < writer.indexOf('Self.thumbnail(source)'), 'hash checked before avatar decoding');
check(writer.includes('ShareContainer.prepareTargets(root: root)'), 'snapshot index excluded from backup');
check(composer.includes('guard !ended else { return }'), 'Close and delayed Done finish only once');
check(composer.includes('self.activeItemIndex == index'), 'old provider progress cannot replace a new item');
check(!writer.includes('username') && !writer.includes('avatarURL('), 'no logins or avatar network in snapshot');
for (const source of [writer, processor]) check(!/Timer\(|Timer\.|scheduledTimer|while true|repeating:/.test(source), 'no periodic share work');
check(chats.includes('ShareTargetSnapshot.canInclude') && chats.includes('loadShareChatsAsync()'), 'authoritative visibility projection');
check(read('ios/UNYRI/Auth/AuthRepository.swift').includes('UnyriShareLifecycle.retire?()'), 'synchronous session retirement');
for (const path of ['ios/UNYRI/Auth/SessionStore.swift','ios/UNYRI/Auth/AccountRegistry.swift']) check(read(path).includes('ShareContainer.purge('), 'purger hook: ' + path);
check(read('ios/UNYRI/Support/UserSettings.swift').includes('unyri.share.requiresAuthentication'), 'immediate app lock privacy fence');
check(composer.includes('ShareContainer.requiresAuthentication(root: root)') && read('ios/Shared/ShareContainer.swift').includes('SharePrivacy/app-lock.json'), 'atomic cross-process privacy fence');
const plist = read('ios/UNYRIShare/Info.plist');
check(plist.includes('<key>NSFaceIDUsageDescription</key>'), 'Face ID reason');
check(!plist.includes('IntentsSupported'), 'no system share row');
for (const language of ['en','ru','uk']) check(read(`ios/UNYRIShare/${language}.lproj/InfoPlist.strings`).includes('NSFaceIDUsageDescription'), 'localized Face ID: ' + language);
for (const path of ['ios/UNYRI/UNYRI.entitlements','ios/UNYRIShare/UNYRIShare.entitlements']) check(!read(path).includes('keychain-access-groups'), 'unchanged default Keychain group');
check(read('docs/ANDROID_PORT_FROM_IOS.md').includes('Share extension phase 1'), 'Android parity recorded');
console.log(`ShareExtensionIsolation source/localization/project contracts passed: ${checks} checks`);
