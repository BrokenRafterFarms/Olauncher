# Update Gradle and AGP to 9.3.0

The goal is to resolve the upgrade block by removing the deprecated `android.defaults.buildfeatures.buildconfig` property from `gradle.properties` and then upgrading the project to AGP 9.3.0 and a compatible Gradle version.

## Proposed Changes

### [Component Name] Gradle Configuration

#### [MODIFY] [gradle.properties](file:///Volumes/Samsung_Drive/Github/Olauncher/gradle.properties)
- Remove `android.defaults.buildfeatures.buildconfig=true`. This property is deprecated and removed in AGP 9.0+, and its use is blocked. The project already explicitly enables `buildConfig` in `app/build.gradle`, so this change is safe.

#### [MODIFY] [libs.versions.toml](file:///Volumes/Samsung_Drive/Github/Olauncher/gradle/libs.versions.toml)
- Update `gradle` version to `9.3.0`.

#### [MODIFY] [gradle-wrapper.properties](file:///Volumes/Samsung_Drive/Github/Olauncher/gradle/wrapper/gradle-wrapper.properties)
- Update `distributionUrl` to use Gradle `9.6.1` to ensure compatibility with AGP 9.3.0.

## Verification Plan

### Automated Tests
- Run `./gradlew clean assembleDebug` to verify the project builds with the new versions.
- Run `gradle_sync` to ensure Android Studio recognizes the new project structure.

### Manual Verification
- Verify that `BuildConfig` is still generated and accessible in the code (e.g., by checking if `Extensions.kt` still compiles).
