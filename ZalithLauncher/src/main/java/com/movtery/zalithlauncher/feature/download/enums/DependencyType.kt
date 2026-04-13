package com.movtery.zalithlauncher.feature.download.enums

/**
 * Dependency relation type for mods, with a dedicated color for each type so the UI can
 * distinguish them more easily.
 *
 * @param curseforge Type identifier used by CurseForge.
 * @param modrinth Type identifier used by Modrinth.
 * @param color Display color for this dependency type.
 */
enum class DependencyType(val curseforge: String?, val modrinth: String?, val color: Int) {
    /**
     * Required: this dependency is mandatory. Without it, the project will not run correctly.
     *
     * CurseForge: "3"
     * Modrinth: "required"
     * Color: 0x4CFF9800 (orange, 30% alpha)
     */
    REQUIRED("3", "required", 0x4CFF9800),

    /**
     * Optional: this dependency is not required, but may add extra features or integrations.
     *
     * CurseForge: "2"
     * Modrinth: "optional"
     * Color: 0x4C34C759 (light green, 30% alpha)
     */
    OPTIONAL("2", "optional", 0x4C34C759),

    /**
     * Incompatible: this indicates a conflict with another project or dependency and should not
     * be installed together.
     *
     * CurseForge: "5"
     * Modrinth: "incompatible"
     * Color: 0x4CEF5350 (light red, 30% alpha)
     */
    INCOMPATIBLE("5", "incompatible", 0x4CEF5350),

    /**
     * Embedded: this dependency is already bundled inside the project, so the user does not need
     * to install it separately.
     *
     * CurseForge: "1"
     * Modrinth: "embedded"
     * Color: 0x4CFFD54F (light yellow, 30% alpha)
     */
    EMBEDDED("1", "embedded", 0x4CFFD54F),

    /**
     * Tool: this dependency is used for development or tooling, and is not required to run the project.
     *
     * CurseForge: "4"
     * Modrinth: null
     * Color: 0x4CBDBDBD (gray, 30% alpha)
     */
    TOOL("4", null, 0x4CBDBDBD),

    /**
     * Include: additional bundled support files or resources associated with the project.
     *
     * CurseForge: "6"
     * Modrinth: null
     * Color: 0x4C9575CD (purple, 30% alpha)
     */
    INCLUDE("6", null, 0x4C9575CD)
}