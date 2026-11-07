"""Generate a standalone simulator target for actual production SwiftUI views."""
from pathlib import Path
import hashlib
import json
import sys

root = Path(sys.argv[1]).resolve()
source = root / "source"
host = root / "presentation-host"
host.mkdir(exist_ok=True)
theme_source = source / "ios/UNYRI/UI/Theme/UnyriTheme.swift"
theme = theme_source.read_text(encoding="utf-8")
def declaration(marker):
    start = theme.index(marker)
    opening = theme.index("{", start)
    level = 1
    end = opening + 1
    while level:
        if theme[end] == "{": level += 1
        elif theme[end] == "}": level -= 1
        end += 1
    return theme[start:end]
# Compile the actual shared material, color and button styles. The fixture
# replaces only account/network/SDK ownership seams, never visual styling.
theme_host = host / "HostTheme.swift"
theme_host.write_text("import SwiftUI\nimport UIKit\n" + declaration("extension Color {") + "\n"
    + theme[theme.index("private struct UnyriAppColorStyleKey"):theme.index("/// AppStorage")]
    + declaration("enum UnyriColors {") + "\n@MainActor\n" + declaration("enum UnyriHaptics {")
    + "\n" + declaration("private struct UnyriPressFeedback:")
    + "\n" + declaration("struct UnyriQuietButtonStyle:"), encoding="utf-8")
files = [source / "ios/Tests/ConferencePresentationHost/Host.swift",
         source / "ios/Tests/ConferencePresentationHost/Adapters.swift", theme_host,
         source / "ios/UNYRI/Conferences/ConferenceViews.swift",
         source / "ios/UNYRI/Conferences/ConferenceDirectoryModels.swift",
         source / "ios/UNYRI/Conferences/ConferenceDirectoryView.swift",
         source / "ios/UNYRI/Conferences/ConferenceRoomView.swift",
         source / "ios/UNYRI/Conferences/ConferenceMessageViews.swift",
         source / "ios/UNYRI/UI/Components/UserAvatar.swift",
         source / "ios/UNYRI/Conferences/ConferenceInvite.swift",
         source / "ios/UNYRI/Signal/MessageContentCodec.swift",
         source / "ios/UNYRI/Models/SignalModels.swift",
         source / "ios/UNYRI/Models/MessageModels.swift",
         source / "ios/UNYRI/Support/AppColorStyle.swift",
         source / "ios/UNYRI/Localizable.xcstrings"]
ids = iter(f"{value:024X}" for value in range(1, 130))
project, target, group, products, product, source_phase, framework_phase, resource_phase, proj_list, target_list, proj_debug, proj_release, target_debug, target_release, package, dependency, dependency_build = [next(ids) for _ in range(17)]
objects = []
def add(identity, value): objects.append(identity + " = {" + value + "};")
refs, builds, resources = [], [], []
for path in files:
    ref, build = next(ids), next(ids)
    filetype = "text.json.xcstrings" if path.suffix == ".xcstrings" else "sourcecode.swift"
    add(ref, f'isa = PBXFileReference; lastKnownFileType = {filetype}; path = "{path}"; sourceTree = "<absolute>";')
    add(build, f"isa = PBXBuildFile; fileRef = {ref};")
    refs.append(ref)
    (resources if path.suffix == ".xcstrings" else builds).append(build)
add(product, 'isa = PBXFileReference; explicitFileType = wrapper.application; path = ConferencePresentationHost.app; sourceTree = BUILT_PRODUCTS_DIR;')
test_target, test_product, test_ref, test_build, test_sources, test_frameworks, test_resources, test_list, test_debug, test_release, proxy, target_dependency = [next(ids) for _ in range(12)]
test_source = source / "ios/Tests/ConferencePresentationHost/UITests.swift"
add(test_ref, f'isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = "{test_source}"; sourceTree = "<absolute>";')
add(test_build, f'isa = PBXBuildFile; fileRef = {test_ref};')
add(test_product, 'isa = PBXFileReference; explicitFileType = wrapper.cfbundle; path = ConferencePresentationUITests.xctest; sourceTree = BUILT_PRODUCTS_DIR;')
add(products, f'isa = PBXGroup; children = ({product},{test_product}); name = Products; sourceTree = "<group>";')
add(group, f'isa = PBXGroup; children = ({",".join(refs + [test_ref, products])}); sourceTree = "<group>";')
add(dependency_build, f"isa = PBXBuildFile; productRef = {dependency};")
add(source_phase, f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({",".join(builds)}); runOnlyForDeploymentPostprocessing = 0;')
add(framework_phase, f'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = ({dependency_build}); runOnlyForDeploymentPostprocessing = 0;')
add(resource_phase, f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({",".join(resources)}); runOnlyForDeploymentPostprocessing = 0;')
add(target, f'isa = PBXNativeTarget; buildConfigurationList = {target_list}; buildPhases = ({source_phase},{framework_phase},{resource_phase}); buildRules = (); dependencies = (); name = ConferencePresentationHost; packageProductDependencies = ({dependency}); productName = ConferencePresentationHost; productReference = {product}; productType = "com.apple.product-type.application";')
add(package, f'isa = XCLocalSwiftPackageReference; relativePath = "{source / "ios/ConferenceLiveKit"}";')
add(dependency, f'isa = XCSwiftPackageProductDependency; package = {package}; productName = LiveKit;')
add(proxy, f'isa = PBXContainerItemProxy; containerPortal = {project}; proxyType = 1; remoteGlobalIDString = {target}; remoteInfo = ConferencePresentationHost;')
add(target_dependency, f'isa = PBXTargetDependency; target = {target}; targetProxy = {proxy};')
add(test_sources, f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({test_build}); runOnlyForDeploymentPostprocessing = 0;')
add(test_frameworks, 'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
add(test_resources, 'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
add(test_target, f'isa = PBXNativeTarget; buildConfigurationList = {test_list}; buildPhases = ({test_sources},{test_frameworks},{test_resources}); buildRules = (); dependencies = ({target_dependency}); name = ConferencePresentationUITests; productName = ConferencePresentationUITests; productReference = {test_product}; productType = "com.apple.product-type.bundle.ui-testing";')
add(project, f'isa = PBXProject; attributes = {{LastUpgradeCheck = 2650; TargetAttributes = {{{test_target} = {{TestTargetID = {target};}};}};}}; buildConfigurationList = {proj_list}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en,Base); mainGroup = {group}; packageReferences = ({package}); productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({target},{test_target});')
common = 'CLANG_ENABLE_MODULES = YES; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SDKROOT = iphoneos; SWIFT_VERSION = 5.0;'
settings = common + ' GENERATE_INFOPLIST_FILE = YES; INFOPLIST_KEY_CFBundleDisplayName = "Conference UI fixture"; INFOPLIST_KEY_UILaunchScreen_Generation = YES; INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES; INFOPLIST_KEY_UISupportedInterfaceOrientations = "UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight"; PRODUCT_BUNDLE_IDENTIFIER = com.coco.qa.conference.presentation; PRODUCT_NAME = "$(TARGET_NAME)"; TARGETED_DEVICE_FAMILY = 1; CODE_SIGNING_ALLOWED = NO; LD_RUNPATH_SEARCH_PATHS = "$(inherited) @executable_path/Frameworks";'
for identity, name, options in [(proj_debug,"Debug",common),(proj_release,"Release",common),(target_debug,"Debug",settings),(target_release,"Release",settings)]:
    add(identity, f'isa = XCBuildConfiguration; buildSettings = {{{options}}}; name = {name};')
add(proj_list, f'isa = XCConfigurationList; buildConfigurations = ({proj_debug},{proj_release}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;')
add(target_list, f'isa = XCConfigurationList; buildConfigurations = ({target_debug},{target_release}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;')
test_settings = common + ' GENERATE_INFOPLIST_FILE = YES; PRODUCT_BUNDLE_IDENTIFIER = com.coco.qa.conference.presentation.tests; PRODUCT_NAME = "$(TARGET_NAME)"; TEST_TARGET_NAME = ConferencePresentationHost; TARGETED_DEVICE_FAMILY = 1; CODE_SIGNING_ALLOWED = NO; LD_RUNPATH_SEARCH_PATHS = "$(inherited) @executable_path/Frameworks @loader_path/Frameworks";'
for identity, name in [(test_debug,"Debug"),(test_release,"Release")]:
    add(identity, f'isa = XCBuildConfiguration; buildSettings = {{{test_settings}}}; name = {name};')
add(test_list, f'isa = XCConfigurationList; buildConfigurations = ({test_debug},{test_release}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Debug;')
project_folder = host / "ConferencePresentationHost.xcodeproj"
project_folder.mkdir(exist_ok=True)
(project_folder / "project.pbxproj").write_text('// !$*UTF8*$!\n{archiveVersion = 1; classes = {}; objectVersion = 56; objects = {' + "\n".join(objects) + '}; rootObject = ' + project + ';}\n')
(project_folder / "xcshareddata/xcschemes").mkdir(parents=True, exist_ok=True)
(project_folder / "xcshareddata/xcschemes/ConferencePresentationHost.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2650" version="1.3">
 <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
  <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="NO"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="ConferencePresentationHost.app" BlueprintName="ConferencePresentationHost" ReferencedContainer="container:ConferencePresentationHost.xcodeproj"/></BuildActionEntry>
  <BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="NO"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{test_target}" BuildableName="ConferencePresentationUITests.xctest" BlueprintName="ConferencePresentationUITests" ReferencedContainer="container:ConferencePresentationHost.xcodeproj"/></BuildActionEntry>
 </BuildActionEntries></BuildAction>
 <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{test_target}" BuildableName="ConferencePresentationUITests.xctest" BlueprintName="ConferencePresentationUITests" ReferencedContainer="container:ConferencePresentationHost.xcodeproj"/></TestableReference></Testables></TestAction>
 <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="ConferencePresentationHost.app" BlueprintName="ConferencePresentationHost" ReferencedContainer="container:ConferencePresentationHost.xcodeproj"/></BuildableProductRunnable></LaunchAction>
</Scheme>''')
(host / "source-evidence.json").write_text(json.dumps({str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files + [theme_source]},indent=2))
print("PRESENTATION_HOST_GENERATED")
