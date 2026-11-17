package com.sleepmood.app

import java.io.File

/**
 * All entries, one JSON object per line, in the app's private storage. Adding appends a line;
 * changing or deleting rewrites the file through a temp file and a rename, so a crash mid-write
 * cannot leave half a file. The whole history is small enough to hold in memory.
 */
class Store(private val file: File) {

    fun load(): List<Entry> =
        if (!file.exists()) emptyList()
        else file.readLines().filter { it.isNotBlank() }.map(Entry::fromJson)

    fun add(e: Entry) {
        file.appendText(e.toJson() + "\n")
    }

    fun replaceAll(entries: List<Entry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(entries.joinToString("") { it.toJson() + "\n" })
        check(tmp.renameTo(file)) { "could not replace $file" }
    }
}
