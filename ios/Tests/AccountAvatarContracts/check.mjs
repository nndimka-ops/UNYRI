import {readFileSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
export const readSource = path => readFileSync(resolve(root, path), 'utf8');
function block(source, marker) {
    const start = source.indexOf(marker);
    assert(start >= 0, 'missing production method: ' + marker);
    let end = source.indexOf('{', start) + 1, depth = 1;
    while (depth && end < source.length) { depth += (source[end] === '{') - (source[end] === '}'); end++; }
    assert.equal(depth, 0);
    return source.slice(start, end);
}

// Wiring checks complement executable Foundation/JVM contracts. Removing any
// cleanup/profile/view hook must break CI even when the isolated store is sound.
export function verifySources(read = readSource) {
    const users = read('ios/UNYRI/Users/UsersRepository.swift');
    const registry = read('ios/UNYRI/Auth/AccountRegistry.swift');
    const session = read('ios/UNYRI/Auth/SessionStore.swift');
    const view = read('ios/UNYRI/UI/Components/AccountAvatar.swift');
    const rows = read('ios/UNYRI/UI/Profile/AccountSwitcherViews.swift');
    const cached = block(users, 'func cachedMe()');
    assert(!/ownProfile\s*=/.test(cached.replace(/ownProfile ==/g, 'canonical ==')), 'disk getter publishes ownProfile');
    assert(cached.includes('keepAccountAvatar(cached)'), 'cached own profile hook');
    assert(block(users, 'private func cacheProfile(').includes('keepAccountAvatar(profile)'), 'server own profile hook');
    const copy = block(users, 'private func copyOwnAccountAvatar(');
    assert(copy.includes('ticket.revision ==') && copy.includes('directoryIdentityIsCurrent') && copy.includes('id.lowercased() == directoryOwner.lowercased()'), 'owner and revision gate');
    assert(!copy.includes('let profile = ownProfile'), 'thumbnail identity must not depend on published profile');
    assert(block(users, 'private func enqueueAccountAvatarWrite(').includes('guard removed || jpeg != nil else { throw'), 'failed encode must retry');
    assert(block(users, 'func avatarURL(').includes('copyOwnAccountAvatar('), 'resolver hook');
    const erase = block(registry, 'static func erase(_ account:');
    assert(erase.includes('eraseAvatar(slot: account.slot.id)'), 'single-account purge hook');
    assert(erase.indexOf('try eraseKeychain(') < erase.indexOf('eraseAvatar(slot:'), 'secrets before thumbnail purge');
    assert(block(registry, 'private static func eraseAvatar(').includes('AccountAvatarStore.device.purge(slot: slot)'), 'slot purger implementation');
    assert(block(registry, 'static func eraseUnusedSlot(').includes('AccountAvatarStore.device.purge(slot: slot.id)'), 'unused-slot purge hook');
    const all = block(session, 'static func erase() async throws');
    assert(all.includes('AccountAvatarStore.device.purgeAll()'), 'last-account purge hook');
    assert(all.indexOf('try erasePrivateKeychain()') < all.indexOf('AccountAvatarStore.device.purgeAll()'), 'last-account secrets before thumbnails');
    assert(view.includes('_image = State(initialValue: AccountAvatarImage.cached(slot: slot, owner: owner))'), 'warm first render must use memory');
    assert(view.includes('if image != nil {') && view.includes('Task.detached'), 'cold decode only on worker');
    assert(rows.includes('UnyriAccountAvatar(record: record, size: 28,') && rows.includes('UnyriAccountAvatar(record: record, size: 36,') && rows.includes('UnyriAccountAvatar(record: record, size: 32,'), 'A/C and existing Settings use thumbnails');
    assert(!rows.includes('UserAvatar(url: nil, name: record.displayName'), 'initials-only row regression');
    const walk = block(read('ios/UITests/UNYRIWalkthroughUITests.swift'), 'private func accountAvatarsProbe()');
    assert(walk.includes('value as? String') && (walk.match(/^\s+assertPhoto\(/gm) || []).length >= 4, 'walkthrough requires rendered photos');
    const workspaces = read('android/app/src/main/java/com/messenger/app/data/auth/AccountWorkspaces.kt');
    assert(block(workspaces, 'fun record(').includes('eraseActiveAvatar()'), 'Android signed-out purge hook');
    assert(block(workspaces, 'fun removeInactive(').includes('avatarStore.purge(key)'), 'Android inactive purge hook');
    assert(block(workspaces, 'fun eraseActiveAvatar(').includes('avatarStore.purgeAll()'), 'Android orphan sweep');
    const app = read('android/app/src/main/java/com/messenger/app/MessengerApp.kt');
    const cleanup = block(app, 'clearAccountData =');
    assert(cleanup.includes('ownAccountAvatar?.retire()') && cleanup.includes('accountWorkspaces.eraseActiveAvatar()'), 'Android cleanup hook');
    assert(cleanup.indexOf('eraseAllAccountSecrets()') < cleanup.indexOf('ownAccountAvatar?.retire()'), 'Android secrets before thumbnails');
    const callback = block(app, 'onResolved =');
    assert(callback.indexOf('if (id != me)') >= 0 && callback.indexOf('if (id != me)') < callback.indexOf('cachedMe()'), 'peer resolution must not read own disk profile');
    const androidView = read('android/app/src/main/java/com/messenger/app/ui/components/AccountAvatar.kt');
    assert(androidView.includes('produceState(AccountAvatarImages.peek('), 'Android warm first render');
    const main = read('android/app/src/main/java/com/messenger/app/ui/MainScreen.kt');
    assert(!/Box\(Modifier\.size\([^\n]+\.background[^\n]+\n\s+com\.messenger\.app\.ui\.components\.AccountAvatar/.test(main), 'double avatar background');
    assert(!read('android/app/src/main/java/com/messenger/app/ui/profile/ProfileScreen.kt').includes('DrawerAccounts('), 'unrequested Android Settings switcher');
}

verifySources();
console.log('Account avatar lifecycle, first-render and walkthrough wiring passed');
