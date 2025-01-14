/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.archive;

import static com.android.tools.build.bundletool.model.version.VersionGuardedFeature.ARCHIVED_APK_GENERATION;
import static com.android.tools.build.bundletool.model.version.VersionGuardedFeature.STORE_ARCHIVE_ENABLED_BY_DEFAULT;
import static com.google.common.base.Preconditions.checkNotNull;

import com.android.aapt.Resources.ResourceTable;
import com.android.tools.build.bundletool.io.TempDirectory;
import com.android.tools.build.bundletool.model.AndroidManifest;
import com.android.tools.build.bundletool.model.AppBundle;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.model.ModuleSplit;
import com.android.tools.build.bundletool.model.ResourceId;
import com.android.tools.build.bundletool.model.ResourceInjector;
import com.android.tools.build.bundletool.model.ResourceTableEntry;
import com.android.tools.build.bundletool.model.exceptions.InvalidCommandException;
import com.android.tools.build.bundletool.model.utils.ResourcesUtils;
import com.android.tools.build.bundletool.model.version.BundleToolVersion;
import com.android.tools.build.bundletool.model.version.Version;
import com.android.tools.build.bundletool.splitters.ResourceAnalyzer;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Generates archived apk based on provided app bundle. Leaves only minimal manifest, only required
 * resources and two custom actions to clear app cache and to wake up an app.
 */
public final class ArchivedApksGenerator {
  public static final String APP_STORE_PACKAGE_NAME_RESOURCE_NAME =
      "reactivation_app_store_package_name";
  public static final String PLAY_STORE_PACKAGE_NAME = "com.android.vending";

  private static final String ARCHIVED_CLASSES_DEX_PATH = "dex/classes.dex";

  private final TempDirectory globalTempDir;

  @Inject
  ArchivedApksGenerator(TempDirectory globalTempDir) {
    this.globalTempDir = globalTempDir;
  }

  public ModuleSplit generateArchivedApk(
      AppBundle appBundle, Optional<String> customAppStorePackageName) throws IOException {
    validateRequest(appBundle);

    BundleModule baseModule = appBundle.getBaseModule();

    AndroidManifest archivedManifest =
        ArchivedAndroidManifestUtils.createArchivedManifest(baseModule.getAndroidManifest());
    ResourceTable archivedResourceTable =
        getArchivedResourceTable(
            appBundle, baseModule, archivedManifest, customAppStorePackageName);
    Path archivedClassesDexFile = getArchivedClassesDexFile();

    return ModuleSplit.forArchive(
        baseModule, archivedManifest, archivedResourceTable, archivedClassesDexFile);
  }

  private void validateRequest(AppBundle appBundle) {
    checkNotNull(appBundle);
    Version bundletoolVersion =
        BundleToolVersion.getVersionFromBundleConfig(appBundle.getBundleConfig());
    if (!ARCHIVED_APK_GENERATION.enabledForVersion(bundletoolVersion)) {
      throw InvalidCommandException.builder()
          .withInternalMessage(
              String.format(
                  "Archived APK can only be generated for bundles built with version %s or higher.",
                  ARCHIVED_APK_GENERATION.getEnabledSinceVersion()))
          .build();
    }

    Optional<Boolean> storeArchiveConfig = appBundle.getStoreArchive();
    boolean isStoreArchiveEnabledByDefault =
        STORE_ARCHIVE_ENABLED_BY_DEFAULT.enabledForVersion(bundletoolVersion);
    if (!storeArchiveConfig.orElse(isStoreArchiveEnabledByDefault)) {
      throw InvalidCommandException.builder()
          .withInternalMessage(
              "Archived APK cannot be generated when Store Archive configuration is disabled.")
          .build();
    }

    if (appBundle.getBaseModule().getAndroidManifest().isHeadless()) {
      throw InvalidCommandException.builder()
          .withInternalMessage(
              "Archived APK can not be generated for applications without a launcher activity.")
          .build();
    }
  }

  private ResourceTable getArchivedResourceTable(
      AppBundle appBundle,
      BundleModule bundleModule,
      AndroidManifest archivedManifest,
      Optional<String> customAppStorePackageName)
      throws IOException {
    ResourceTable.Builder archivedResourceTable = ResourceTable.newBuilder();
    if (bundleModule.getResourceTable().isPresent()) {
    ImmutableSet<ResourceId> referredResources =
        new ResourceAnalyzer(appBundle).findAllAppResourcesReachableFromManifest(archivedManifest);
      archivedResourceTable =
          ResourcesUtils.filterResourceTable(
              bundleModule.getResourceTable().get(),
              /* removeEntryPredicate= */ entry ->
                  !referredResources.contains(entry.getResourceId()),
              /* configValuesFilterFn= */ ResourceTableEntry::getEntry)
              .toBuilder();
    }
    ResourceInjector resourceInjector =
        new ResourceInjector(archivedResourceTable, appBundle.getPackageName());
    resourceInjector.addStringResource(
        APP_STORE_PACKAGE_NAME_RESOURCE_NAME, getAppStorePackageName(customAppStorePackageName));
    return resourceInjector.build();
  }

  private static String getAppStorePackageName(Optional<String> customAppStorePackageName) {
    return customAppStorePackageName.orElse(PLAY_STORE_PACKAGE_NAME);
  }

  private Path getArchivedClassesDexFile() throws IOException {
    Path archivedDexFilePath = Files.createTempFile(globalTempDir.getPath(), "classes", ".dex");
    try (InputStream inputStream = readArchivedClassesDexFile()) {
      Files.copy(inputStream, archivedDexFilePath, StandardCopyOption.REPLACE_EXISTING);
    }
    return archivedDexFilePath;
  }

  private static InputStream readArchivedClassesDexFile() {
    return ArchivedApksGenerator.class.getResourceAsStream(ARCHIVED_CLASSES_DEX_PATH);
  }
}
