package com.github.miguelaguiar01.spotless.config.maven

import org.jdom.Element

/**
 * Maven's plugin-configuration merge (`Xpp3Dom.mergeXpp3Dom(dominant, recessive)`) on JDOM:
 * dominant values win, recessive children are added when absent, `combine.self="override"` keeps
 * the dominant subtree as is, `combine.children="append"` concatenates instead of merging by name,
 * and `combine.id`/`combine.keys` pair children by identity.
 */
object XmlMerge {
    private const val SELF = "combine.self"
    private const val CHILDREN = "combine.children"
    private const val ID = "combine.id"
    private const val KEYS = "combine.keys"

    fun merge(dominant: Element?, recessive: Element?): Element? {
        if (dominant == null) return recessive?.clone()
        if (recessive == null) return dominant.clone()
        return mergeInto(dominant.clone(), recessive)
    }

    private fun mergeInto(dominant: Element, recessive: Element): Element {
        if (dominant.getAttributeValue(SELF) == "override") return dominant

        for (attr in recessive.attributes) {
            if (dominant.getAttribute(attr.name) == null) dominant.setAttribute(attr.name, attr.value)
        }
        if (dominant.textTrim.isEmpty() && dominant.children.isEmpty() && recessive.textTrim.isNotEmpty()) {
            dominant.text = recessive.text
        }

        val append = dominant.getAttributeValue(CHILDREN) == "append"
        if (append) {
            // recessive children first, then the dominant ones
            val own = dominant.children.map { it.clone() }
            dominant.removeContent()
            recessive.children.forEach { dominant.addContent(it.clone()) }
            own.forEach { dominant.addContent(it) }
            return dominant
        }

        val keys = dominant.getAttributeValue(KEYS)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        for (recChild in recessive.children) {
            val match = dominant.children.firstOrNull { domChild -> sameChild(domChild, recChild, keys) }
            if (match == null) {
                dominant.addContent(recChild.clone())
            } else {
                mergeInto(match, recChild)
            }
        }
        return dominant
    }

    private fun sameChild(a: Element, b: Element, keys: List<String>?): Boolean {
        if (a.name != b.name) return false
        val idA = a.getAttributeValue(ID)
        val idB = b.getAttributeValue(ID)
        if (idA != null || idB != null) return idA == idB
        if (!keys.isNullOrEmpty()) {
            return keys.all { key -> a.getChildTextTrim(key) == b.getChildTextTrim(key) }
        }
        return true
    }
}
