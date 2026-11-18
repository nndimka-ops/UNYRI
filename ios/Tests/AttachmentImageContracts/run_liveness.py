"""Exercise the actual mounted source and Photos observer with Foundation I/O doubles.

No UIKit/Photos pixels are validated here. Production load/selection/revision and
observer methods are extracted verbatim; only native services are controlled.
"""
from pathlib import Path
import argparse
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--swiftc', default='swiftc')
parser.add_argument('--source-only', action='store_true')
args = parser.parse_args()


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def block(source, marker):
    start = source.index(marker)
    end, depth = source.index('{', start) + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


images = read('ios/UNYRI/UI/Chats/AttachmentImages.swift')
photos = read('ios/UNYRI/UI/Chats/PhotosAttachmentThumbnail.swift')
chat = read('ios/UNYRI/UI/Chats/ChatScreen.swift')
viewer = read('ios/UNYRI/UI/Chats/UnyriMediaViewer.swift')
viewer_start = chat.index('return AnyView(UnyriMediaViewer(items: mediaItems.contains')
hosted_viewer = chat[viewer_start:chat.index('return AnyView(Color.black)', viewer_start)]
assert hosted_viewer.index('.environment(\\.chatMessagesRepository, mediaRepository)') < hosted_viewer.index('.unyriLocale())'), 'Inject the repository inside the independently hosted viewer content'
assert '@Environment(\\.chatMessagesRepository)' in images and 'repository ?? environmentRepository' in images
viewer_body = block(block(viewer, 'struct UnyriMediaViewer:'), '    var body: some View {')
assert '.environment(\\.scenePhase, viewerScenePhase)' in viewer_body, 'Bridge activity inside the independently hosted viewer tree'
viewer_phase = block(viewer, '    private var viewerScenePhase: ScenePhase')
assert 'viewerActive ? .active : .inactive' in viewer_phase
binding = block(images, 'struct UnyriAttachmentImageBinding:')
binding_gate = block(binding, '    private var allowed: Bool')
display_gate = block(binding, '    private var displayed: Bool')
work_default = next(line.strip() for line in binding.splitlines() if line.strip().startswith('var workAllowed ='))
assert 'active: phase == .active' in binding_gate
assert 'visibleWhenMounted ? mounted : visible' in binding_gate
assert 'mounted = true; configure()' in images and 'mounted = false; source.stop()' in images
assert 'visibleWhenMounted: visibleWhenMounted' in block(images, 'struct UnyriAttachmentImage<'), 'Forward mounted visibility through the shared view wrapper'
bubble = block(chat, 'private struct AttachmentBubbleView:')
image_binding = bubble[bubble.index('.modifier(UnyriAttachmentImageBinding('):bubble.index('.onChange(of: attachmentImage.value')]
assert 'onVisibility: { mediaVisible = $0 }' in image_binding and 'previewDecodeAllowed' not in image_binding, 'The binding alone owns decode visibility/activity; the bubble gate owns transfers/playback'
assert '.modifier(UnyriMediaVisibility' not in bubble, 'One visibility observer must drive both decode admission and bubble controls'
walk = read('ios/UITests/UNYRIWalkthroughUITests.swift')
viewer_role = walk[walk.index('case "viewer":'):walk.index('case "pdf":')]
assert viewer_role.index('Persisted album photo did not render') < viewer_role.index('photo.tap()'), 'Native relaunch check must precede viewer cache warming'
for marker in ['private struct UnyriReplyComposerPreview:', 'private struct PinnedMessagePreviewRow:']:
    assert 'visibleWhenMounted: true' in block(chat, marker), 'Mount-driven thumbnail must not wait for scroll visibility: ' + marker
environment = block(images, 'extension EnvironmentValues {')
assert 'var chatMessagesRepository:' in environment
assert 'private extension EnvironmentValues' not in images
assert 'var chatMessagesRepository:' not in chat, 'One shared, module-visible repository environment value'
availability = block(images, '    private func availabilityChanged()')
assert 'guard displayed, allowed else { return }' in availability
assert 'needsRevalidation = true' in availability and 'refresh(start: true)' in availability
assert 'guard effective?.url != lastURL else { return }' not in availability
assert 'revisions[url.path] == revision' in images
assert 'pending.removeValue(forKey: key as String)?.task.cancel()' in chat
assert 'guard current else { return nil }' in chat
follow = block(photos, '    func follow(')
assert 'PHAsset.fetchAssets(withLocalIdentifiers: [id]' in follow
assert 'quality.sharp' not in follow, 'A sharp cache hit cannot skip refresh after hidden edits'
assert 'refreshTask?.cancel()' in block(photos, '    func stop()')
assert 'UnyriAttachmentImages.invalidate' in block(photos, '    private func load()')
print('PASS: shared/hosted-viewer environment access, mounted revision revalidation and Photos resume wiring')
if args.source_only:
    print('SKIP: mounted-source and hidden-Photos-edit Swift execution (--source-only)')
    raise SystemExit(0)

# Execute the actual bridge and binding gate with an inactive inherited phase.
# Only their native inputs are replaced; do not hardcode the policy's active input.
gate_fixtures = '''
enum ScenePhase { case active, inactive, background }
struct ViewerActivityFixture {
    var viewerActive: Bool
''' + viewer_phase.replace('private var', 'var', 1) + '''
}
struct AttachmentBindingGateFixture {
    var request: AttachmentImageRequest
''' + work_default + '''
    var viewerSurface = false
    var phase: ScenePhase
    var mediaWork = MediaWork()
    var visible = true
    var visibleWhenMounted = false
    var mounted = true
    var visibilityKnown = true
    var rendersImage = true
    var menu = false
    struct MediaWork { var viewerOpen = true }
''' + binding_gate.replace('private var', 'var', 1) + '\n' + display_gate.replace('private var', 'var', 1) + '\n}\n'
production = '\n'.join(block(images, marker) for marker in [
    'struct AttachmentImageRequest:', 'struct AttachmentImageValue {',
    '@MainActor enum UnyriAttachmentImages {',
    '@MainActor final class UnyriAttachmentImageSource:',
])
production += '\n' + block(photos, '@MainActor private final class PhotosAttachmentThumbnailSource:')
with tempfile.TemporaryDirectory(prefix='unyri-image-liveness-') as directory:
    directory = Path(directory)
    source = directory / 'liveness.swift'
    source.write_text(read('ios/Tests/AttachmentImageContracts/Liveness.swift') + '\n' + gate_fixtures + production, encoding='utf-8')
    binary = directory / 'liveness.exe'
    subprocess.run([args.swiftc, '-swift-version', '5', '-parse-as-library',
                    str(ROOT / 'ios/UNYRI/Support/AttachmentImagePolicy.swift'),
                    str(source), '-o', str(binary)], check=True, timeout=90)
    subprocess.run([str(binary)], check=True, timeout=30)
