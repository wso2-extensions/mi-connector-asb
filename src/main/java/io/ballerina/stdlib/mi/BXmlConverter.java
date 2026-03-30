/*
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.mi;

import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BXml;
import io.ballerina.runtime.api.values.BXmlItem;
import io.ballerina.runtime.internal.values.XmlPi;
import io.ballerina.runtime.internal.values.XmlSequence;
import org.apache.axiom.om.*;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.Map;

public class BXmlConverter {
    private static final OMFactory factory = OMAbstractFactory.getOMFactory();
    private static final String XMLNS_PREFIX = "xmlns:";

    private static boolean isNamespaceDeclarationAttribute(String attributeName) {
        return attributeName.equals(BXmlItem.XMLNS_PREFIX)
                || attributeName.startsWith(XMLNS_PREFIX)
                || attributeName.equals(BXmlItem.XMLNS_NS_URI_PREFIX)
                || attributeName.startsWith(BXmlItem.XMLNS_NS_URI_PREFIX);
    }

    private static String getNamespacePrefix(String attributeName) {
        if (attributeName.equals(BXmlItem.XMLNS_PREFIX) || attributeName.equals(BXmlItem.XMLNS_NS_URI_PREFIX)) {
            return "";
        }
        if (attributeName.startsWith(XMLNS_PREFIX)) {
            return attributeName.substring(XMLNS_PREFIX.length());
        }
        if (attributeName.startsWith(BXmlItem.XMLNS_NS_URI_PREFIX)) {
            return attributeName.substring(BXmlItem.XMLNS_NS_URI_PREFIX.length());
        }
        return "";
    }

    static Pair<String, String> extractNamespace(String value) {

        if (value.startsWith("{")) {
            int index = value.indexOf("}");
            String ns = value.substring(1, index);
            String localName = value.substring(index + 1);
            return Pair.of(ns, localName);
        }

        return Pair.of("", value);
    }

    public static OMElement toOMElement(BXml bXml) {
        if (bXml instanceof XmlSequence xmlSequence) {
            if (!xmlSequence.isEmpty()) {
                BXml firstItem = xmlSequence.getItem(0);
                return toOMElement(firstItem);
            }
            return null;
        }

        if (!(bXml instanceof BXmlItem xmlItem)) {
            return null;
        }

        OMNamespace namespace = factory.createOMNamespace(xmlItem.getQName().getNamespaceURI(),
                xmlItem.getQName().getPrefix());
        BMap<BString, BString> bMap = xmlItem.getAttributesMap();

        OMElement rootElement = factory.createOMElement(xmlItem.getQName().getLocalPart(), namespace);
        // create a map of namespaces with key:"" and value:null
        Map<String, OMNamespace> namespaceMap = new HashMap<>();
        namespaceMap.put("", null);
        if (namespace != null && namespace.getNamespaceURI() != null && !namespace.getNamespaceURI().isEmpty()) {
            namespaceMap.put(namespace.getNamespaceURI(), namespace);
        }

        for (Map.Entry<BString, BString> entry : bMap.entrySet()) {
            String attributeName = entry.getKey().getValue();
            if (isNamespaceDeclarationAttribute(attributeName)) {
                String prefix = getNamespacePrefix(attributeName);
                OMNamespace omNamespace = factory.createOMNamespace(entry.getValue().getValue(), prefix);
                namespaceMap.put(entry.getValue().getValue(), omNamespace);
            }
        }
        for (Map.Entry<BString, BString> attribute : bMap.entrySet()) {
            String attributeName = attribute.getKey().getValue();
            if (!isNamespaceDeclarationAttribute(attributeName)) {
                Pair<String, String> pair = extractNamespace(attribute.getKey().getValue());
                OMAttribute omattribute = factory.createOMAttribute(pair.getRight(), namespaceMap.get(pair.getLeft()),
                        attribute.getValue().getValue());
                rootElement.addAttribute(omattribute);
            }

        }
        addChildrenElements(rootElement, bXml);
        return rootElement;
    }

    static void addChildrenElements(OMElement rootElement, BXml bXml) {
        BXmlItem xmlItem = (BXmlItem) bXml;
        for (int i = 0; i < xmlItem.children().size(); i++) {
            BXml child = xmlItem.children().getItem(i);
            switch (child.getNodeType()) {
                case ELEMENT:
                    OMElement childElement = toOMElement(child);
                    if (childElement != null) {
                        rootElement.addChild(childElement);
                    }
                    break;
                case TEXT:
                    OMText omText = factory.createOMText(rootElement, child.getTextValue());
                    rootElement.addChild(omText);
                    break;
                case COMMENT:
                    OMComment omComment = factory.createOMComment(rootElement, child.getTextValue());
                    rootElement.addChild(omComment);
                    break;
                case PI:
                    XmlPi xmlPi = (XmlPi) child;
                    OMProcessingInstruction omProcessingInstruction = factory.createOMProcessingInstruction(null,
                            xmlPi.getTarget(), xmlPi.getData());
                    rootElement.addChild(omProcessingInstruction);
                    break;
                default:
                    break;
            }
        }
    }
}
