package task3.data

/**
 * File operations stay expect/actual (java.io is unavailable in common).
 * All network transport is Ktor in common code, see task2.data.Network.
 */
expect fun deleteFile(path: String)
