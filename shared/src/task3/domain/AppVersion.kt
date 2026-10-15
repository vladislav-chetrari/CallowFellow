package task3.domain

/**
 * Single source of the CallowFellow app version.
 *
 * Single source is gradle/libs.versions.toml [versions].app/app-code,
 * mirrored in androidApp/module.yaml and here.
 *
 * History:
 * - 0.1.0 — transfer from Week 5 AI-Advent-Challenge to standalone
 *   CallowFellow repo, app rename com.callowfellow/label CallowFellow,
 *   Task-artifact cleanup.
 */
object AppVersion {
    const val NAME = "CallowFellow"
    const val VERSION = "0.1.0"
    const val VERSION_CODE = 1

    /** Short signature for the settings footer / diagnostics. */
    fun label(): String = "$NAME v$VERSION ($VERSION_CODE)"
}
