import assert from 'node:assert/strict';
import {readSource, verifySources} from './check.mjs';

const mutations = [
    ['ios/UNYRI/Users/UsersRepository.swift', 'keepAccountAvatar(cached)', 'ownProfile = cached'],
    ['ios/UNYRI/Users/UsersRepository.swift', 'keepAccountAvatar(profile)', 'removedHook(profile)'],
    ['ios/UNYRI/Users/UsersRepository.swift', 'guard removed || jpeg != nil else { throw CancellationError() }', 'guard removed || jpeg != nil else { return }'],
    ['ios/UNYRI/Auth/AccountRegistry.swift', 'eraseAvatar(slot: account.slot.id)', 'removedHook(slot: account.slot.id)'],
    ['ios/UNYRI/Auth/AccountRegistry.swift', 'AccountAvatarStore.device.purge(slot: slot.id)', 'removedHook(slot: slot.id)'],
    ['ios/UNYRI/Auth/SessionStore.swift', 'AccountAvatarStore.device.purgeAll()', 'removedHook()'],
    ['android/app/src/main/java/com/messenger/app/data/auth/AccountWorkspaces.kt', 'avatarStore.purge(key)', 'removedHook(key)'],
    ['android/app/src/main/java/com/messenger/app/data/auth/AccountWorkspaces.kt', 'eraseActiveAvatar()', 'removedHook()'],
    ['android/app/src/main/java/com/messenger/app/MessengerApp.kt', 'accountWorkspaces.eraseActiveAvatar()', 'removedHook()'],
    ['ios/UNYRI/UI/Components/AccountAvatar.swift', 'State(initialValue: AccountAvatarImage.cached(slot: slot, owner: owner))', 'State(initialValue: nil)'],
    ['android/app/src/main/java/com/messenger/app/ui/components/AccountAvatar.kt', 'produceState(AccountAvatarImages.peek(account.key, owner)', 'produceState<Bitmap?>(null'],
    ['ios/UNYRI/UI/Profile/AccountSwitcherViews.swift', 'UnyriAccountAvatar(record: record, size: 28,', 'UserAvatar(url: nil, name: record.displayName, size: 28,'],
    ['ios/UITests/UNYRIWalkthroughUITests.swift', 'value as? String', 'label as? String'],
];
for (const [path, before, after] of mutations) {
    const original = readSource(path);
    assert(original.includes(before), 'mutation did not target production: ' + before);
    assert.throws(() => verifySources(file => file === path ? original.replaceAll(before, after) : readSource(file)), undefined,
        'regression survived: ' + before);
}
console.log(`Account avatar adversarial wiring mutations rejected: ${mutations.length}/${mutations.length}`);
