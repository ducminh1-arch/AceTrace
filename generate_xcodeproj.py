import os, uuid

def make_id(name):
    # Generates a deterministic 24-char hex ID from name
    return uuid.uuid5(uuid.NAMESPACE_DNS, name).hex[:24].upper()

source_files = [
    ("AceTraceApp.swift", "AceTraceApp.swift"),
    ("Info.plist", "Info.plist"),
    ("Assets.xcassets", "Assets.xcassets"),
    ("Core/Curve/BezierPath.swift", "BezierPath.swift"),
    ("Core/Curve/CatmullRomPath.swift", "CatmullRomPath.swift"),
    ("Core/Curve/TimeMapping.swift", "TimeMapping.swift"),
    ("Core/Curve/TrajectoryPath.swift", "TrajectoryPath.swift"),
    ("Core/Model/Project.swift", "Project.swift"),
    ("Core/Render/OverlayRenderer.swift", "OverlayRenderer.swift"),
    ("Core/Video/FrameIndexer.swift", "FrameIndexer.swift"),
    ("Core/Video/PlayerController.swift", "PlayerController.swift"),
    ("Core/Video/VideoExporter.swift", "VideoExporter.swift"),
    ("Features/EditorView.swift", "EditorView.swift"),
    ("Features/SpikeTestView.swift", "SpikeTestView.swift"),
]

test_files = [
    ("AceTraceTests/CurveGoldenTests.swift", "CurveGoldenTests.swift"),
    ("AceTraceTests/TimeMappingGoldenTests.swift", "TimeMappingGoldenTests.swift"),
]

# File references
filerefs = []
buildfiles_sources = []
buildfiles_resources = []

for rel_path, filename in source_files:
    fid = make_id("fileref_" + rel_path)
    bid = make_id("buildfile_" + rel_path)
    if filename.endswith(".swift"):
        filerefs.append(f'\t\t{fid} /* {filename} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = "{filename}"; sourceTree = "<group>"; }};')
        buildfiles_sources.append(f'\t\t{bid} /* {filename} in Sources */ = {{isa = PBXBuildFile; fileRef = {fid} /* {filename} */; }};')
    elif filename.endswith(".xcassets"):
        filerefs.append(f'\t\t{fid} /* {filename} */ = {{isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = "{filename}"; sourceTree = "<group>"; }};')
        buildfiles_resources.append(f'\t\t{bid} /* {filename} in Resources */ = {{isa = PBXBuildFile; fileRef = {fid} /* {filename} */; }};')
    elif filename.endswith(".plist"):
        filerefs.append(f'\t\t{fid} /* {filename} */ = {{isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = "{filename}"; sourceTree = "<group>"; }};')

# Group hierarchy
curve_fids = [f"{make_id('fileref_Core/Curve/' + f)} /* {f} */" for f in ["BezierPath.swift", "CatmullRomPath.swift", "TimeMapping.swift", "TrajectoryPath.swift"]]
model_fids = [f"{make_id('fileref_Core/Model/Project.swift')} /* Project.swift */"]
render_fids = [f"{make_id('fileref_Core/Render/OverlayRenderer.swift')} /* OverlayRenderer.swift */"]
video_fids = [f"{make_id('fileref_Core/Video/' + f)} /* {f} */" for f in ["FrameIndexer.swift", "PlayerController.swift", "VideoExporter.swift"]]
feat_fids = [f"{make_id('fileref_Features/' + f)} /* {f} */" for f in ["EditorView.swift", "SpikeTestView.swift"]]

test_fids = []
for rel_path, filename in test_files:
    fid = make_id("fileref_" + rel_path)
    filerefs.append(f'\t\t{fid} /* {filename} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = "{filename}"; sourceTree = "<group>"; }};')
    test_fids.append(f"{fid} /* {filename} */")

curve_group_id = make_id("group_curve")
model_group_id = make_id("group_model")
render_group_id = make_id("group_render")
video_group_id = make_id("group_video")
core_group_id = make_id("group_core")
feat_group_id = make_id("group_feat")
test_group_id = make_id("group_tests")
main_group_id = make_id("group_main")
root_group_id = make_id("group_root")

proj_id = make_id("project_acetrace")
target_id = make_id("target_acetrace")
sources_build_phase_id = make_id("sources_phase")
resources_build_phase_id = make_id("resources_phase")
frameworks_build_phase_id = make_id("frameworks_phase")

config_debug_id = make_id("config_debug")
config_release_id = make_id("config_release")
config_list_target_id = make_id("config_list_target")

proj_config_debug_id = make_id("proj_config_debug")
proj_config_release_id = make_id("proj_config_release")
config_list_proj_id = make_id("config_list_proj")

pbxproj_content = f"""// !$*UTF8*$!
{{
	archiveVersion = 1;
	classes = {{
	}};
	objectVersion = 56;
	objects = {{

/* Begin PBXBuildFile section */
{chr(10).join(buildfiles_sources)}
{chr(10).join(buildfiles_resources)}
/* End PBXBuildFile section */

/* Begin PBXFileReference section */
{chr(10).join(filerefs)}
/* End PBXFileReference section */

/* Begin PBXFrameworksBuildPhase section */
		{frameworks_build_phase_id} /* Frameworks */ = {{
			isa = PBXFrameworksBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXFrameworksBuildPhase section */

/* Begin PBXGroup section */
		{root_group_id} = {{
			isa = PBXGroup;
			children = (
				{main_group_id} /* AceTrace */,
			);
			sourceTree = "<group>";
		}};
		{main_group_id} /* AceTrace */ = {{
			isa = PBXGroup;
			children = (
				{make_id("fileref_AceTraceApp.swift")} /* AceTraceApp.swift */,
				{core_group_id} /* Core */,
				{feat_group_id} /* Features */,
				{test_group_id} /* AceTraceTests */,
				{make_id("fileref_Assets.xcassets")} /* Assets.xcassets */,
				{make_id("fileref_Info.plist")} /* Info.plist */,
			);
			path = "";
			sourceTree = "<group>";
		}};
		{core_group_id} /* Core */ = {{
			isa = PBXGroup;
			children = (
				{curve_group_id} /* Curve */,
				{model_group_id} /* Model */,
				{render_group_id} /* Render */,
				{video_group_id} /* Video */,
			);
			path = "Core";
			sourceTree = "<group>";
		}};
		{curve_group_id} /* Curve */ = {{
			isa = PBXGroup;
			children = (
{chr(10).join([f'\t\t\t\t{c},' for c in curve_fids])}
			);
			path = "Curve";
			sourceTree = "<group>";
		}};
		{model_group_id} /* Model */ = {{
			isa = PBXGroup;
			children = (
{chr(10).join([f'\t\t\t\t{c},' for c in model_fids])}
			);
			path = "Model";
			sourceTree = "<group>";
		}};
		{render_group_id} /* Render */ = {{
			isa = PBXGroup;
			children = (
{chr(10).join([f'\t\t\t\t{c},' for c in render_fids])}
			);
			path = "Render";
			sourceTree = "<group>";
		}};
		{video_group_id} /* Video */ = {{
			isa = PBXGroup;
			children = (
{chr(10).join([f'\t\t\t\t{c},' for c in video_fids])}
			);
			path = "Video";
			sourceTree = "<group>";
		}};
		{feat_group_id} /* Features */ = {{
			isa = PBXGroup;
			children = (
{chr(10).join([f'\t\t\t\t{c},' for c in feat_fids])}
			);
			path = "Features";
			sourceTree = "<group>";
		}};
		{test_group_id} /* AceTraceTests */ = {{
			isa = PBXGroup;
			children = (
{chr(10).join([f'\t\t\t\t{c},' for c in test_fids])}
			);
			path = "AceTraceTests";
			sourceTree = "<group>";
		}};
/* End PBXGroup section */

/* Begin PBXNativeTarget section */
		{target_id} /* AceTrace */ = {{
			isa = PBXNativeTarget;
			buildConfigurationList = {config_list_target_id} /* Build configuration list for PBXNativeTarget "AceTrace" */;
			buildPhases = (
				{sources_build_phase_id} /* Sources */,
				{frameworks_build_phase_id} /* Frameworks */,
				{resources_build_phase_id} /* Resources */,
			);
			buildRules = (
			);
			dependencies = (
			);
			name = AceTrace;
			productName = AceTrace;
			productType = "com.apple.product-type.application";
		}};
/* End PBXNativeTarget section */

/* Begin PBXProject section */
		{proj_id} /* Project object */ = {{
			isa = PBXProject;
			attributes = {{
				BuildIndependentTargetsInParallel = 1;
				LastSwiftUpdateCheck = 1520;
				LastUpgradeCheck = 1520;
				TargetAttributes = {{
					{target_id} = {{
						CreatedOnToolsVersion = 15.2;
					}};
				}};
			}};
			buildConfigurationList = {config_list_proj_id} /* Build configuration list for PBXProject "AceTrace" */;
			compatibilityVersion = "Xcode 14.0";
			developmentRegion = en;
			hasScannedForEncodings = 0;
			knownRegions = (
				en,
				Base,
			);
			mainGroup = {root_group_id};
			productRefGroup = {root_group_id};
			projectDirPath = "";
			projectRoot = "";
			targets = (
				{target_id} /* AceTrace */,
			);
		}};
/* End PBXProject section */

/* Begin PBXResourcesBuildPhase section */
		{resources_build_phase_id} /* Resources */ = {{
			isa = PBXResourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
{chr(10).join([f'\t\t\t\t{b},' for b in buildfiles_resources])}
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXResourcesBuildPhase section */

/* Begin PBXSourcesBuildPhase section */
		{sources_build_phase_id} /* Sources */ = {{
			isa = PBXSourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
{chr(10).join([f'\t\t\t\t{b},' for b in buildfiles_sources])}
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};
/* End PBXSourcesBuildPhase section */

/* Begin XCBuildConfiguration section */
		{proj_config_debug_id} /* Debug */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
				ALWAYS_SEARCH_USER_PATHS = NO;
				CLANG_ANALYZER_NONNULL = YES;
				CLANG_CXX_LANGUAGE_STANDARD = "gnu++20";
				CLANG_ENABLE_MODULES = YES;
				CLANG_ENABLE_OBJC_ARC = YES;
				ENABLE_STRICT_OBJC_MSGSEND = YES;
				ENABLE_TESTABILITY = YES;
				GCC_NO_COMMON_BLOCKS = YES;
				GCC_OPTIMIZATION_LEVEL = 0;
				IPHONEOS_DEPLOYMENT_TARGET = 16.0;
				MTL_ENABLE_DEBUG_INFO = INCLUDE_SOURCE;
				ONLY_ACTIVE_ARCH = YES;
				SDKROOT = iphoneos;
				SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;
				SWIFT_OPTIMIZATION_LEVEL = "-Onone";
			}};
			name = Debug;
		}};
		{proj_config_release_id} /* Release */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
				ALWAYS_SEARCH_USER_PATHS = NO;
				CLANG_ANALYZER_NONNULL = YES;
				CLANG_CXX_LANGUAGE_STANDARD = "gnu++20";
				CLANG_ENABLE_MODULES = YES;
				CLANG_ENABLE_OBJC_ARC = YES;
				ENABLE_STRICT_OBJC_MSGSEND = YES;
				GCC_NO_COMMON_BLOCKS = YES;
				IPHONEOS_DEPLOYMENT_TARGET = 16.0;
				MTL_ENABLE_DEBUG_INFO = NO;
				SDKROOT = iphoneos;
				SWIFT_COMPILATION_MODE = wholemodule;
				SWIFT_OPTIMIZATION_LEVEL = "-O";
			}};
			name = Release;
		}};
		{config_debug_id} /* Debug */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
				ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
				ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor;
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				GENERATE_INFOPLIST_FILE = NO;
				INFOPLIST_FILE = Info.plist;
				LD_RUNPATH_SEARCH_PATHS = (
					"$(inherited)",
					"@executable_path/Frameworks",
				);
				MARKETING_VERSION = 1.0;
				PRODUCT_BUNDLE_IDENTIFIER = com.acetrace.app;
				PRODUCT_NAME = "$(TARGET_NAME)";
				SWIFT_EMIT_LOC_STRINGS = YES;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = "1,2";
			}};
			name = Debug;
		}};
		{config_release_id} /* Release */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
				ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
				ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor;
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				GENERATE_INFOPLIST_FILE = NO;
				INFOPLIST_FILE = Info.plist;
				LD_RUNPATH_SEARCH_PATHS = (
					"$(inherited)",
					"@executable_path/Frameworks",
				);
				MARKETING_VERSION = 1.0;
				PRODUCT_BUNDLE_IDENTIFIER = com.acetrace.app;
				PRODUCT_NAME = "$(TARGET_NAME)";
				SWIFT_EMIT_LOC_STRINGS = YES;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = "1,2";
			}};
			name = Release;
		}};
/* End XCBuildConfiguration section */

/* Begin XCConfigurationList section */
		{config_list_proj_id} /* Build configuration list for PBXProject "AceTrace" */ = {{
			isa = XCConfigurationList;
			buildConfigurations = (
				{proj_config_debug_id} /* Debug */,
				{proj_config_release_id} /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		}};
		{config_list_target_id} /* Build configuration list for PBXNativeTarget "AceTrace" */ = {{
			isa = XCConfigurationList;
			buildConfigurations = (
				{config_debug_id} /* Debug */,
				{config_release_id} /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		}};
/* End XCConfigurationList section */

	}};
	rootObject = {proj_id} /* Project object */;
}}
"""

out_dir = r"d:\AceTrace\aceTrace_ios\AceTrace.xcodeproj"
os.makedirs(out_dir, exist_ok=True)
pbxproj_path = os.path.join(out_dir, "project.pbxproj")
with open(pbxproj_path, "w", encoding="utf-8") as f:
    f.write(pbxproj_content)

print(f"Generated {pbxproj_path} successfully!")
