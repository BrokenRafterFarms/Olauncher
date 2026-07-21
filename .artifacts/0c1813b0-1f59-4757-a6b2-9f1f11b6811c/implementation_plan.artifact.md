# Fix Hidden Apps Showing in App Drawer After Reboot

The user reports that hidden apps appear in the regular app drawer after a power cycle on their physical Samsung device.

## Analysis

### 1. Missing Separator in Migration Logic (Primary Cause)
In `Utils.kt`, the `upgradeHiddenApps` function is responsible for migrating hidden app keys to include the user handle. However, it is missing the `|` separator:
```kotlin
else newHiddenAppsSet.add(hiddenPackage + android.os.Process.myUserHandle().toString())
```
This causes keys like `com.android.settings` to be migrated to `com.android.settingsUserHandle{0}` instead of `com.android.settings|UserHandle{0}`.

When the device reboots, the launcher refreshes the app list. `getAppsList` checks for hidden apps using the correct `|` separator. Because the keys in `SharedPreferences` were broken by the migration logic, the match fails. Consequently, the hidden apps are treated as regular apps and displayed in the drawer.

### 2. Redundant/Incorrect Loading in AppDrawerFragment
In `AppDrawerFragment.kt`, the `onStart` method unconditionally calls `viewModel.getAppList(true)`. This requests hidden apps to be included in the general `appList` LiveData, which is then used to populate the drawer. While search filtering might handle this, it's safer to only load hidden apps when they are actually needed (e.g., for setting home apps or in the hidden folder).

## Proposed Changes

### [Component] Helper Utilities
#### [MODIFY] [Utils.kt](file:///Volumes/Samsung_Drive/Github/Olauncher/app/src/main/java/app/olauncher/helper/Utils.kt)
- Fix `upgradeHiddenApps` to include the `|` separator when migrating keys. This will fix the persistence issue across reboots for apps migrated from older versions.

### [Component] App Drawer
#### [MODIFY] [AppDrawerFragment.kt](file:///Volumes/Samsung_Drive/Github/Olauncher/app/src/main/java/app/olauncher/ui/AppDrawerFragment.kt)
- Update `onStart` to load hidden apps only when appropriate for the current `flag`. For the regular launch view, we will load only non-hidden apps to ensure they don't appear in the list.

## Verification Plan

### Manual Verification
1. Hide an app (e.g., "Calculator").
2. Verify it is gone from the main drawer.
3. Reboot the device.
4. Verify the app remains hidden from the main drawer.
5. Check the "Hidden Folder" to ensure it's still there.
6. Verify that you can still set a hidden app as a home app (by long-pressing a home app slot, which uses a different flag).
