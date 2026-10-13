package com.epaflix.fytorium

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NetworkConfigTest {
    @Test fun releaseForbidsCleartextAndHasNoDebugConfig() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertFalse(manifest.contains("networkSecurityConfig"))
        assertFalse(File("src/main/res/xml/network_security_config.xml").exists())
        assertFalse(File("src/release/AndroidManifest.xml").exists())
        val debug = File("src/debug/AndroidManifest.xml").readText()
        assertFalse(debug.contains("usesCleartextTraffic=\"true\""))
        assertTrue(debug.contains("@xml/network_security_config"))
    }
    @Test fun debugAllowsOnlyExactStackHosts() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/debug/res/xml/network_security_config.xml"))
        assertEquals("false", document.getElementsByTagName("base-config").item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        val domains = document.getElementsByTagName("domain")
        assertEquals(2, domains.length)
        assertEquals(setOf("app", "oidc"), (0 until domains.length).map { domains.item(it).textContent.trim() }.toSet())
        for (i in 0 until domains.length) {
            assertEquals("false", domains.item(i).attributes.getNamedItem("includeSubdomains").nodeValue)
        }
    }
}
