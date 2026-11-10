package com.github.miguelaguiar01.spotless.config.maven

import com.intellij.openapi.util.JDOMUtil
import junit.framework.TestCase

class XmlMergeTest : TestCase() {

    private fun xml(s: String) = JDOMUtil.load(s.trimIndent())
    private fun str(e: org.jdom.Element?) = JDOMUtil.writeElement(e!!).replace(Regex("\\s+"), "")

    fun testDominantValueWinsAndRecessiveChildrenAreAdded() {
        val dominant = xml("<configuration><encoding>ISO-8859-1</encoding><java><googleJavaFormat/></java></configuration>")
        val recessive = xml("<configuration><encoding>UTF-8</encoding><lineEndings>UNIX</lineEndings><java><importOrder/></java></configuration>")
        val merged = XmlMerge.merge(dominant, recessive)
        assertEquals(
            str(xml("<configuration><encoding>ISO-8859-1</encoding><java><googleJavaFormat/><importOrder/></java><lineEndings>UNIX</lineEndings></configuration>")),
            str(merged),
        )
    }

    fun testCombineSelfOverrideKeepsDominantSubtree() {
        val dominant = xml("<configuration><java combine.self=\"override\"><palantirJavaFormat/></java></configuration>")
        val recessive = xml("<configuration><java><googleJavaFormat/></java></configuration>")
        val merged = XmlMerge.merge(dominant, recessive)!!
        assertEquals(listOf("palantirJavaFormat"), merged.getChild("java").children.map { it.name })
    }

    fun testCombineChildrenAppend() {
        val dominant = xml("<configuration><formats combine.children=\"append\"><format><includes>b</includes></format></formats></configuration>")
        val recessive = xml("<configuration><formats><format><includes>a</includes></format></formats></configuration>")
        val merged = XmlMerge.merge(dominant, recessive)!!
        assertEquals(listOf("a", "b"), merged.getChild("formats").children.map { it.getChildText("includes") })
    }

    fun testNullSidesClone() {
        val only = xml("<configuration><encoding>UTF-8</encoding></configuration>")
        assertEquals(str(only), str(XmlMerge.merge(null, only)))
        assertEquals(str(only), str(XmlMerge.merge(only, null)))
        assertNull(XmlMerge.merge(null, null))
    }

    fun testInterpolationResolvesChainsAndReportsUnknown() {
        val warnings = mutableListOf<String>()
        val out = MavenSpotlessConfigExtractor.interpolate(
            "<file>\${project.basedir}/\${hdr}</file><x>\${nope}</x>",
            mapOf("project.basedir" to "/p", "hdr" to "\${name}.txt", "name" to "LICENSE"),
            warnings,
        )
        assertEquals("<file>/p/LICENSE.txt</file><x>\${nope}</x>", out)
        assertEquals(listOf("Unresolved property \${nope} in spotless configuration"), warnings)
    }
}
