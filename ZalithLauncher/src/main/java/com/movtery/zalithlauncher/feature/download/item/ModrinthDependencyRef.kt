package com.movtery.zalithlauncher.feature.download.item

import com.movtery.zalithlauncher.feature.download.enums.DependencyType

/**
 * Exact dependency reference returned by Modrinth for a specific version.
 *
 * @param projectId Dependency project ID.
 * @param versionId Exact dependency version ID, if Modrinth provides one.
 * @param dependencyType Dependency relation type.
 */
data class ModrinthDependencyRef(
    val projectId: String,
    val versionId: String?,
    val dependencyType: DependencyType
)