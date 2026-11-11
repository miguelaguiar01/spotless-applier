package com.github.miguelaguiar01.spotless.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WslPathMapperTest {
    private val mapper = WslPathMapper("Ubuntu-24.04")

    @Test
    fun wholePaths() {
        assertEquals("/home/u/p/pom.xml", mapper.toLinuxPath("""\\wsl.localhost\Ubuntu-24.04\home\u\p\pom.xml"""))
        assertEquals("/home/u", mapper.toLinuxPath("//wsl.localhost/Ubuntu-24.04/home/u"))
        assertEquals("/home/u", mapper.toLinuxPath("""\\wsl$\Ubuntu-24.04\home\u"""))
        assertEquals("/", mapper.toLinuxPath("""\\wsl.localhost\Ubuntu-24.04"""))
        assertEquals("/", mapper.toLinuxPath("""\\wsl.localhost\Ubuntu-24.04\"""))
        assertEquals("/home/u/my dir/x.txt", mapper.toLinuxPath("""\\wsl.localhost\Ubuntu-24.04\home\u\my dir\x.txt"""))
    }

    @Test
    fun otherPathsUntouched() {
        assertEquals("""C:\Users\u\x""", mapper.toLinuxPath("""C:\Users\u\x"""))
        assertEquals("""\\wsl.localhost\Debian\home\u""", mapper.toLinuxPath("""\\wsl.localhost\Debian\home\u"""))
        assertEquals("""\\wsl.localhost\Ubuntu-24.04-old\home""", mapper.toLinuxPath("""\\wsl.localhost\Ubuntu-24.04-old\home"""))
        assertEquals("/already/linux", mapper.toLinuxPath("/already/linux"))
        assertFalse(mapper.isWslPath("""C:\x"""))
        assertTrue(mapper.isWslPath("//wsl.localhost/Ubuntu-24.04/x"))
    }

    @Test
    fun embeddedInText() {
        val xml = """<licenseHeader><file>\\wsl.localhost\Ubuntu-24.04\home\u\p\header.txt</file></licenseHeader>""" +
            """<other>//wsl.localhost/Ubuntu-24.04/home/u/p/a.xml and C:\keep\this</other>"""
        assertEquals(
            "<licenseHeader><file>/home/u/p/header.txt</file></licenseHeader>" +
                """<other>/home/u/p/a.xml and C:\keep\this</other>""",
            mapper.toLinuxInText(xml),
        )
    }

    @Test
    fun distributionId() {
        assertEquals("Ubuntu-24.04", WslPathMapper.distributionIdOf("//wsl.localhost/Ubuntu-24.04/home"))
        assertEquals("Debian", WslPathMapper.distributionIdOf("""\\wsl$\Debian\home"""))
        assertNull(WslPathMapper.distributionIdOf("""C:\Develop"""))
        assertNull(WslPathMapper.distributionIdOf("/home/u"))
    }
}
