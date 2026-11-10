package com.github.miguelaguiar01.spotless.worker.maven.adapter;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.codehaus.plexus.configuration.DefaultPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Builds a {@link PlexusConfiguration} tree from XML text without depending on Xpp3Dom. */
final class XmlToPlexus {
    private XmlToPlexus() {}

    static DefaultPlexusConfiguration parse(String xml) throws Exception {
        return convert(parseDocument(xml).getDocumentElement());
    }

    static Document parseDocument(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    static DefaultPlexusConfiguration convert(Element element) {
        DefaultPlexusConfiguration cfg = new DefaultPlexusConfiguration(element.getTagName());
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            cfg.setAttribute(a.getName(), a.getValue());
        }
        StringBuilder text = new StringBuilder();
        boolean hasElementChildren = false;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                hasElementChildren = true;
                cfg.addChild(convert((Element) n));
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(n.getNodeValue());
            }
        }
        if (!hasElementChildren) {
            // Maven (Xpp3Dom) trims element text.
            String value = text.toString().trim();
            if (!value.isEmpty()) cfg.setValue(value);
        }
        return cfg;
    }
}
