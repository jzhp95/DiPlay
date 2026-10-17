package com.shilapi.xcertplay

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Default resources are Chinese so services and third-party launchers never fall back to English. */
class SettingsTranslationsTest {
    @Test fun onlyDefaultChineseResourceTablesRemain() {
        for (module in listOf(File("."), File("../shared"), File("../samples/home"), File("../samples/maphost"))) {
            val res = File(module, "src/main/res")
            val locales = res.listFiles()?.filter { it.name.matches(Regex("values-[a-z]{2}(?:-.*)?")) }.orEmpty()
            assertEquals("$module retains a selectable resource locale", emptyList<File>(), locales)
            val strings = File(res, "values").listFiles { f -> f.extension == "xml" }!!
                .flatMap { file ->
                    val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
                    (0 until nodes.length).map { nodes.item(it) }
                }.associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
            assertTrue("$module has no Chinese default strings", strings.values.any { value -> value.any { it in '\u4e00'..'\u9fff' } })
        }
        assertFalse(File("src/main/res/xml/locales_config.xml").exists())
    }
}
