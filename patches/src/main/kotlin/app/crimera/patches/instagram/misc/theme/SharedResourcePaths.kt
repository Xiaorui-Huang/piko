/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.theme

import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

private val pathMapEntry = Regex("""\{[^}]*}""")
private val pathMapName = Regex(""""name"\s*:\s*"([^"]+)"""")
private val pathMapAlias = Regex(""""alias"\s*:\s*"([^"]+)"""")
private val resourcePathValue = Regex(""">\s*(res/[^<]+?)\s*<""")

private val themeValuesFiles =
    listOf("values", "values-night", "values-v31", "values-night-v31").flatMap { directory ->
        listOf("colors.xml", "styles.xml").map { "res/$directory/$it" }
    }

/**
 * aapt dedupes identical resource files, so a second resource sharing one is decoded into a values
 * file whose value is a made-up alias path (e.g. `res/color/mock_…xml`). Re-encoding an edited
 * values file then writes that alias into the table, and the app crashes on launch with
 * `Resources$NotFoundException`. Put the real archive paths back.
 *
 * Can be dropped once Morphe ships morphe-patcher 1.15 (MorpheApp/morphe-patcher#220).
 */
internal fun ResourcePatchContext.restoreSharedResourcePaths() {
    val pathMap = get("res").parentFile.parentFile.parentFile.resolve("path-map.json")
    if (!pathMap.isFile) return

    val archiveNames =
        pathMapEntry
            .findAll(pathMap.readText())
            .mapNotNull { entry ->
                val name = pathMapName.find(entry.value)?.groupValues?.get(1)
                val alias = pathMapAlias.find(entry.value)?.groupValues?.get(1)
                if (name != null && alias != null && name != alias) alias to name else null
            }.toMap()
    if (archiveNames.isEmpty()) return

    themeValuesFiles
        .filter { path ->
            val file = get(path)
            file.isFile &&
                resourcePathValue.findAll(file.readText()).any { it.groupValues[1] in archiveNames }
        }
        .forEach { path ->
            document(path).use { document ->
                val elements = document.documentElement.getElementsByTagName("*")
                for (index in 0 until elements.length) {
                    val element = elements.item(index) as Element
                    if (element.childNodes.length != 1) continue
                    archiveNames[element.textContent.trim()]?.let { element.textContent = it }
                }
            }
        }
}
