package task3.data

/** File size in bytes (-1 if the file is missing). */
expect fun fileSizeBytes(path: String): Long

/**
 * Quick integrity check of a downloaded model without native code: size only.
 * No format-specific checks by design (.litertlm is an opaque bundle,
 * the runtime itself complains about a corrupt file on load).
 * Returns null if the file looks intact, otherwise error text for the UI
 * (with size — for remote diagnostics).
 */
fun validateModelFile(path: String, expectedSizeMb: Int): String? {
    val size = fileSizeBytes(path)
    if (size <= 0) return "Файл пустой или недоступен — скачай заново"
    val expected = expectedSizeMb.toLong() * 1024 * 1024
    if (size < expected * 8 / 10) {
        return "Файл оборван: ${size / 1024 / 1024} МБ из ~$expectedSizeMb МБ — удали и скачай заново"
    }
    return null
}
