/*
 * Copyright 2011-2012 Kevin Seim
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.beanio.internal.config.xml;

import java.io.*;
import java.net.*;
import java.util.*;

import org.beanio.BeanIOConfigurationException;
import org.beanio.internal.config.*;
import org.beanio.internal.util.*;
import org.w3c.dom.*;

/**
 * Parses a mapping file into {@link BeanIOConfig} objects.  A <tt>BeanIOConfig</tt>
 * is produced for each mapping file imported by the mapping file being parsed,
 * and the entire collection is returned from {@link #loadConfiguration(InputStream, Properties)}
 * 
 * <p>This class is not thread safe and a new instance should be created for parsing
 * each input stream.
 * 
 * @author Kevin Seim
 * @since 1.2.1
 */
public class XmlMappingParser implements StringUtil.PropertySource {

    private static final boolean propertySubstitutionEnabled = 
        Boolean.valueOf(Settings.getInstance().getProperty(Settings.PROPERTY_SUBSTITUTION_ENABLED));
    
    /* used to read XML into a DOM object */
    private XmlMappingReader reader;
    /* the mapping currently being parsed */
    private XmlMapping mapping;
    /* a Map of all loaded mappings (except the root) */
    private Map<String,XmlMapping> mappings;
    /* the ClassLoader for loading imported resources */
    private ClassLoader classLoader;
    /* custom Properties provided by the client for property expansion */
    private Properties properties;
    
    private LinkedList<Include> includeStack = new LinkedList<Include>();

    /**
     * Constructs a new <tt>XmlMappingParser</tt>.
     * @param classLoader the {@link ClassLoader} for loading imported resources
     * @param reader the XML mapping reader for reading XML mapping files
     *   into a DOM object
     */
    public XmlMappingParser(ClassLoader classLoader, XmlMappingReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("loader is null");
        }
        this.reader = reader;
        this.classLoader = classLoader;
    }
    
    /**
     * Reads a mapping file input stream and returns a collection of BeanIO
     * configurations, one for the input stream and one for each imported
     * mapping file (if specified).
     * @param in the input stream to read
     * @param properties the {@link Properties} to use for property substitution
     * @return the collection of parsed BeanIO configuration objects
     * @throws IOException if an I/O error occurs
     * @throws BeanIOConfigurationException if the configuration is invalid
     */
    public Collection<BeanIOConfig> loadConfiguration(InputStream in, Properties properties) 
        throws IOException, BeanIOConfigurationException {
        
        this.properties = properties;
        
        mapping = new XmlMapping();
        mappings = new HashMap<String,XmlMapping>();
        mappings.put("[root]", mapping);
        
        try {
            loadMapping(in);
        }
        catch (BeanIOConfigurationException ex) {
            if (mapping.getLocation() != null) {
                throw new BeanIOConfigurationException("Invalid mapping file '" +
                    mapping.getName() + "': " + ex.getMessage(), ex);
            }
            throw ex;
        }
        
        List<BeanIOConfig> configList = new ArrayList<BeanIOConfig>(mappings.size());
        for (XmlMapping m : mappings.values()) {
            BeanIOConfig config = m.getConfiguration().clone();
            
            // global type handlers are the only elements that need to be imported
            // from other mapping files
            List<TypeHandlerConfig> handlerList = new ArrayList<TypeHandlerConfig>();
            m.addTypeHandlers(handlerList);
            
            config.setTypeHandlerList(handlerList);
            configList.add(config);
        }
        
        return configList;
    }
    
    /**
     * Initiates the parsing of an imported mapping file.
     * <p>After parsing completes, {@link #pop()} must be invoked
     * before continuing.
     * @param name the name of the imported mapping file
     * @param location the location of the imported mapping file
     *   (this should be an absolute URL so that importing the
     *    same mapping more than once can be detected)
     * @return the new Mapping object pushed onto the stack
     *   (this can also be accessed by calling {@link #getMapping()})
     * @see #pop()
     */
    protected final XmlMapping push(String name, String location) {
        XmlMapping m = new XmlMapping(name, location, mapping);
        mappings.put(location, m);
        
        mapping.addImport(m);
        mapping = m;
        return mapping;
    }
    
    /**
     * Completes the parsing of an imported mapping file.
     * @see #push(String, String)
     */
    protected final void pop() {
        mapping = mapping.getParent();
    }
    
    /**
     * Returns the mapping file information actively being parsed, which may change
     * when one mapping file imports another.
     * @return the active mapping information
     */
    protected final XmlMapping getMapping() {
        return mapping;
    }
    
    /**
     * Returns the amount to offset a field position, which is calculated
     * according to included template offset configurations.
     * @return the current field position offset
     */
    protected final int getPositionOffset() {
        if (includeStack.isEmpty()) {
            return 0;
        }
        else {
            return includeStack.getFirst().getOffset();
        }
    }
    
    /**
     * Loads a mapping file from an input stream.
     * @param in the input stream to read
     * @throws IOException if an I/O error occurs
     */
    protected void loadMapping(InputStream in) throws IOException {
        Document document = reader.loadDocument(in);
        loadMapping(document.getDocumentElement());
    }
    
    /**
     * Parses a BeanIO configuration from a DOM element.
     * @param element the root 'beanio' DOM element to parse
     */
    protected void loadMapping(Element element) {
        BeanIOConfig config = mapping.getConfiguration();

        NodeList children = element.getChildNodes();
        for (int i = 0, j = children.getLength(); i < j; i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;

            Element child = (Element) node;
            String name = child.getTagName();
            if ("import".equals(name)) {
                importConfiguration(child);
            }
            else if ("property".equals(name)) {
                String key = getAttribute(child, "name");
                String value = getAttribute(child, "value");
                mapping.setProperty(key, value);
            }
            else if ("typeHandler".equals(name)) {
                TypeHandlerConfig handler = createHandlerConfig(child);
                if (handler.getName() != null &&
                    mapping.isDeclaredGlobalTypeHandler(handler.getName())) {
                    throw new BeanIOConfigurationException(
                        "Duplicate global type handler named '" + handler.getName() + "'");
                }
                config.addTypeHandler(createHandlerConfig(child));
            }
            else if ("template".equals(name)) {
                createTemplate(child);
            }
            else if ("stream".equals(name)) {
                config.addStream(createStreamConfig(child));
            }
        }
    }
    
    /**
     * Parses an <tt>import</tt> DOM element and loads its mapping file.
     * @param element the <tt>import</tt> DOM element
     * @return a new <tt>BeanIOConfig</tt> for the imported resource or file
     */
    protected final XmlMapping importConfiguration(Element element) {
        URL url = null;
        String resource = getAttribute(element, "resource");
        String name = resource;
        
        if (resource.startsWith("classpath:")) {
            resource = resource.substring("classpath:".length()).trim();
            if ("".equals(resource)) {
                throw new BeanIOConfigurationException("Invalid import resource");
            }
            
            url = IOUtil.getResource(classLoader, resource);
            if (url == null) { 
                throw new BeanIOConfigurationException("Resource '" + name + 
                    "' not found in classpath for import");
            }
        }
        else if (resource.startsWith("file:")) {
            resource = resource.substring("file:".length()).trim();
            if ("".equals(resource)) {
                throw new BeanIOConfigurationException("Invalid import resource");
            }
            
            File file = new File(resource);
            if (!file.canRead()) {
                throw new BeanIOConfigurationException("Resource '" + name + 
                    "' not found in file system for import");
            }
            
            try {
                url = file.toURI().toURL();
            }
            catch (MalformedURLException ex) {
                throw new IllegalStateException(ex);
            }
        }
        else {
           throw new BeanIOConfigurationException("Import resource name must begin with 'classpath:' or 'file:'");
        }

        if (mapping.isLoading(url.toString())) {
            throw new BeanIOConfigurationException("Failed to import resource '" + name + 
                "': Circular reference(s) detected");
        }
        
        String key = url.toString();
        
        // check to see if the mapping file has already been loaded
        XmlMapping m = mappings.get(key);
        if (m != null) {
            return m;
        }
        
        InputStream in = null;
        try {
            in = new BufferedInputStream(url.openStream());
            
            // push a new Mapping instance onto the stack for this url
            push(name, key).getConfiguration().setSource(name);
            
            loadMapping(in);
            
            // this is purposely not put in a finally block so that
            // calling methods can know the mapping file that errored
            // if a BeanIOConfigurationException is thrown
            pop();
            
            return mapping;
        }
        catch (IOException ex) {
            throw new BeanIOConfigurationException("Failed to import mapping file '" + name + "'", ex);
        }
        finally {
            IOUtil.closeQuietly(in);
        }
    }

    /**
     * Parses a <tt>TypeHandlerConfig</tt> from a DOM element. 
     * @param element the DOM element to parse
     * @return the new <tt>TypeHandlerConfig</tt>
     */
    protected TypeHandlerConfig createHandlerConfig(Element element) {
        TypeHandlerConfig config = new TypeHandlerConfig();
        config.setName(getAttribute(element, "name"));
        config.setType(getAttribute(element, "type"));
        config.setClassName(getAttribute(element, "class"));
        config.setFormat(getAttribute(element, "format"));
        config.setProperties(createProperties(element));
        return config;
    }
    
    /**
     * Adds a template to the active mapping.
     * @param element the DOM element that defines the template
     */
    protected void createTemplate(Element element) {
        String templateName = element.getAttribute("name");
        if (!mapping.addTemplate(templateName, element)) {
            throw new BeanIOConfigurationException(
                "Duplicate template named '" + templateName + "'");
        }
    }
    
    /**
     * Parses a <tt>Bean</tt> from a DOM element. 
     * @param element the DOM element to parse
     * @return the new <tt>Bean</tt>
     */
    protected BeanConfig createBeanFactory(Element element) {
        BeanConfig config = new BeanConfig();
        config.setClassName(getAttribute(element, "class"));
        config.setProperties(createProperties(element));
        return config;
    }

    /**
     * Parses <tt>Properties</tt> from a DOM element. 
     * @param element the DOM element to parse
     * @return the new <tt>Properties</tt>
     */
    protected Properties createProperties(Element element) {
        Properties props = null;

        NodeList children = element.getChildNodes();
        for (int i = 0, j = children.getLength(); i < j; i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;

            Element child = (Element) node;
            String name = child.getTagName();
            if ("property".equals(name)) {
                if (props == null) {
                    props = new Properties();
                }

                props.put(
                    child.getAttribute("name"),
                    child.getAttribute("value"));
            }
        }
        return props;
    }

    /**
     * Parses a <tt>StreamConfig</tt> from a DOM element. 
     * @param element the <tt>stream</tt> DOM element to parse
     * @return the new <tt>StreamConfig</tt>
     */
    protected StreamConfig createStreamConfig(Element element) {
        StreamConfig config = new StreamConfig();
        config.setName(getAttribute(element, "name"));
        config.setFormat(getAttribute(element, "format"));
        config.setMode(getAttribute(element, "mode"));
        config.setResourceBundle(getAttribute(element, "resourceBundle"));
        config.setStrict(getBooleanAttribute(element, "strict", config.isStrict()));
        populatePropertyConfigOccurs(config, element);
        
        config.setXmlName(getAttribute(element, "xmlName"));
        config.setXmlNamespace(getOptionalAttribute(element, "xmlNamespace"));
        config.setXmlPrefix(getOptionalAttribute(element, "xmlPrefix"));
        config.setXmlType(getOptionalAttribute(element, "xmlType"));
        
        NodeList children = element.getChildNodes();
        for (int i = 0, j = children.getLength(); i < j; i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;

            Element child = (Element) node;
            String name = child.getTagName();
            if ("typeHandler".equals(name)) {
                config.addHandler(createHandlerConfig(child));
            }
            else if ("parser".equals(name)) {
                config.setParserFactory(createBeanFactory(child));
            }
            else if ("record".equals(name)) {
                config.add(createRecordConfig(child));
            }
            else if ("group".equals(name)) {
                config.add(createGroupConfig(child));
            }
        }

        return config;
    }

    /**
     * Parses a group configuration from a DOM element.
     * @param element the <tt>group</tt> DOM element to parse
     * @return the parsed group configuration
     */
    protected GroupConfig createGroupConfig(Element element) {
        GroupConfig config = new GroupConfig();
        populatePropertyConfig(config, element);
        config.setOrder(getIntegerAttribute(element, "order"));
        config.setKey(getAttribute(element, "key"));
        config.setXmlName(getAttribute(element, "xmlName"));
        config.setXmlNamespace(getOptionalAttribute(element, "xmlNamespace"));
        config.setXmlPrefix(getOptionalAttribute(element, "xmlPrefix"));
        config.setXmlType(getOptionalAttribute(element, "xmlType"));
        config.setType(getAttribute(element, "class"));
        
        NodeList children = element.getChildNodes();
        for (int i = 0, j = children.getLength(); i < j; i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;

            Element child = (Element) node;
            String name = child.getTagName();
            if ("record".equals(name)) {
                config.add(createRecordConfig(child));
            }
            else if ("group".equals(name)) {
                config.add(createGroupConfig(child));
            }
        }

        return config;
    }
    
    /**
     * Parses a record configuration from the given DOM element.
     * @param element the <tt>record</tt> DOM element to parse
     * @return the parsed record configuration
     */
    protected RecordConfig createRecordConfig(Element element) {
        RecordConfig segment = new RecordConfig();
        populatePropertyConfig(segment, element);
        segment.setType(getAttribute(element, "class"));
        segment.setKey(getAttribute(element, "key"));
        segment.setTarget(getAttribute(element, "target"));
        segment.setOrder(getIntegerAttribute(element, "order"));
        segment.setMinLength(getIntegerAttribute(element, "minLength"));
        segment.setMaxLength(getUnboundedIntegerAttribute(element, "maxLength", Integer.MAX_VALUE));
        segment.setXmlName(getAttribute(element, "xmlName"));
        segment.setXmlNamespace(getOptionalAttribute(element, "xmlNamespace"));
        segment.setXmlPrefix(getOptionalAttribute(element, "xmlPrefix"));
        segment.setJsonName(getAttribute(element, "jsonName"));
        segment.setJsonType(getAttribute(element, "jsonType"));
        
        String template = getOptionalAttribute(element, "template");
        if (template != null) {
            includeTemplate(segment, template, 0);
        }
        addProperties(segment, element);
        
        return segment;
    }
    
    /**
     * Parses bean properties from the given DOM element.
     * @param config the enclosing bean configuration to add the properties to
     * @param element the <tt>bean</tt> or <tt>record</tt> DOM element to parse
     */
    protected void addProperties(ComponentConfig config, Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0, j = children.getLength(); i < j; i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;

            Element child = (Element) node;
            String name = child.getTagName();
            if ("field".equals(name)) {
                config.add(createFieldConfig(child));
            }
            else if ("segment".equals(name)) {
                config.add(createSegmentConfig(child));
            }
            else if ("property".equals(name)) {
                config.add(createConstantConfig(child));
            }
            else if ("include".equals(name)) {
                includeTemplate(config, child);
            }
        }
    }
    
    /**
     * Includes a template.
     * @param config the parent bean configuration
     * @param element the <tt>include</tt> DOM element to parse
     */
    protected void includeTemplate(ComponentConfig config, Element element) {
        String template = getAttribute(element, "template");
        int offset = getIntAttribute(element, "offset", 0);
        includeTemplate(config, template, offset);
    }
    
    /**
     * Includes a template.
     * @param config the parent bean configuration
     * @param template the name of the template to include
     * @param offset the value to offset configured positions by
     */
    protected void includeTemplate(ComponentConfig config, String template, int offset) {
        Element element = mapping.findTemplate(template);
        
        // validate the template was declared
        if (element == null) {
            throw new BeanIOConfigurationException("Template '" + template + "' not found");
        }
        
        // validate there is no circular reference
        for (Include include : includeStack) {
            if (template.equals(include.getTemplate())) {
                throw new BeanIOConfigurationException(
                    "Circular reference detected in template '" + template + "'");
            }
        }
        
        // adjust the configured offset by any previous offset
        offset += getPositionOffset();
        
        Include inc = new Include(template, offset);
        includeStack.addFirst(inc);
        addProperties(config, element);
        includeStack.removeFirst();
    }
        
    private void populatePropertyConfig(PropertyConfig config, Element element) {
        config.setName(getAttribute(element, "name"));
        config.setGetter(getAttribute(element, "getter"));
        config.setSetter(getAttribute(element, "setter"));
        config.setCollection(getAttribute(element, "collection"));
        populatePropertyConfigOccurs(config, element);
    }
    
    private void populatePropertyConfigOccurs(PropertyConfig config, Element element) {
        if (hasAttribute(element, "occurs")) {
            if (hasAttribute(element, "minOccurs") || hasAttribute(element, "maxOccurs")) {
                throw new BeanIOConfigurationException("occurs cannot be used with minOccurs or maxOccurs");
            }
            
            // parse occurs (e.g. '1', '0-1', '0+', '1+' etc)
            String occurs = getAttribute(element, "occurs");
            if (occurs == null) {
                throw new BeanIOConfigurationException("Invalid occurs '" + occurs + "'");
            }
            
            try {
                if (occurs.endsWith("+")) {
                    config.setMinOccurs(Integer.parseInt(occurs.substring(0, occurs.length() - 1)));
                    config.setMaxOccurs(Integer.MAX_VALUE);
                }
                else {
                    int n = occurs.indexOf('-');
                    if (n < 0) {
                        n = Integer.parseInt(occurs);
                        config.setMinOccurs(n);
                        config.setMaxOccurs(n);
                    }
                    else {
                        config.setMinOccurs(Integer.parseInt(occurs.substring(0, n)));
                        config.setMaxOccurs(Integer.parseInt(occurs.substring(n + 1)));
                    }
                }
            }
            catch (NumberFormatException ex) {
                throw new BeanIOConfigurationException("Invalid occurs '" + occurs + "'", ex);
            }
        }
        else {
            config.setMinOccurs(getIntegerAttribute(element, "minOccurs"));
            config.setMaxOccurs(getUnboundedIntegerAttribute(element, "maxOccurs", Integer.MAX_VALUE));
        }
    }
    
    /**
     * Parses a segment component configuration from a DOM element.
     * @param element the <tt>segment</tt> DOM element to parse
     * @return the parsed segment configuration
     * @since 2.0
     */
    protected SegmentConfig createSegmentConfig(Element element) {
        SegmentConfig config = new SegmentConfig();
        populatePropertyConfig(config, element);
        config.setType(getAttribute(element, "class"));
        config.setLazy(getBooleanAttribute(element, "lazy", config.isLazy()));
        config.setKey(getAttribute(element, "key"));
        config.setTarget(getAttribute(element, "target"));
        config.setXmlName(getAttribute(element, "xmlName"));
        config.setXmlNamespace(getOptionalAttribute(element, "xmlNamespace"));
        config.setXmlPrefix(getOptionalAttribute(element, "xmlPrefix"));
        config.setXmlType(getOptionalAttribute(element, "xmlType"));
        config.setNillable(getBooleanAttribute(element, "nillable", config.isNillable()));
        config.setJsonName(getAttribute(element, "jsonName"));
        config.setJsonType(getAttribute(element, "jsonType"));
        String template = getOptionalAttribute(element, "template");
        if (template != null) {
            includeTemplate(config, template, 0);
        }
        addProperties(config, element);
        return config;
    }

    /**
     * Parses a field configuration from a DOM element.
     * @param element the <tt>field</tt> DOM element to parse
     * @return the parsed field configuration
     */
    protected FieldConfig createFieldConfig(Element element) {
        FieldConfig config = new FieldConfig();
        populatePropertyConfig(config, element);
        
        // adjust the position by the configured include offset
        int position = getIntAttribute(element, "position", -1);
        if (position >= 0) {
            position += getPositionOffset();
            config.setPosition(position);
        }
        
        config.setMinLength(getIntegerAttribute(element, "minLength"));
        config.setMaxLength(getUnboundedIntegerAttribute(element, "maxLength", -1));
        config.setRegex(getAttribute(element, "regex"));
        config.setLiteral(getAttribute(element, "literal"));
        config.setTypeHandler(getTypeHandler(element, "typeHandler"));
        config.setType(getAttribute(element, "type"));
        config.setFormat(getAttribute(element, "format"));
        config.setDefault(getOptionalAttribute(element, "default"));
        config.setRequired(getBooleanAttribute(element, "required", config.isRequired()));
        config.setTrim(getBooleanAttribute(element, "trim", config.isTrim()));
        config.setIdentifier(getBooleanAttribute(element, "rid", 
            config.isIdentifier()));
        config.setBound(!getBooleanAttribute(element, "ignore", false));
        config.setLength(getUnboundedIntegerAttribute(element, "length", -1));
        config.setPadding(getCharacterAttribute(element, "padding"));
        config.setKeepPadding(getBooleanAttribute(element, "keepPadding", config.isKeepPadding()));
        config.setJustify(getAttribute(element, "justify"));
        config.setXmlType(getAttribute(element, "xmlType"));
        config.setXmlName(getAttribute(element, "xmlName"));
        config.setXmlNamespace(getOptionalAttribute(element, "xmlNamespace"));
        config.setXmlPrefix(getOptionalAttribute(element, "xmlPrefix"));
        config.setNillable(getBooleanAttribute(element, "nillable", config.isNillable()));
        config.setJsonName(getAttribute(element, "jsonName"));
        config.setJsonType(getAttribute(element, "jsonType"));
        return config;
    }

    /**
     * Parses a constant component configuration from a DOM element.
     * @param element the <tt>property</tt> DOM element to parse
     * @return the parsed constant configuration
     * @since 2.0
     */
    protected ConstantConfig createConstantConfig(Element element) {
        ConstantConfig config = new ConstantConfig();
        try {
            config.setName(getAttribute(element, "name"));
            config.setGetter(getAttribute(element, "getter"));
            config.setSetter(getAttribute(element, "setter"));
            config.setType(getAttribute(element, "type"));
            config.setTypeHandler(getTypeHandler(element, "typeHandler"));
            config.setValue(getAttribute(element, "format"));
            config.setIdentifier(getBooleanAttribute(element, "rid", 
                config.isIdentifier()));
            config.setValue(getAttribute(element, "value"));
            return config;
        }
        catch (BeanIOConfigurationException ex) {
            throw new BeanIOConfigurationException(
                "Invalid '" + config.getName() + "' property definition: " + ex.getMessage());
        }
    }
    
    /*
     * (non-Javadoc)
     * @see org.beanio.internal.util.StringUtil.PropertiesSource#getProperty(java.lang.String)
     */
    public String getProperty(String key) {
        String value = null;
        if (properties != null) {
            value = properties.getProperty(key);
        }
        if (value == null) {
            Properties mappingProperties = mapping.getProperties();
            if (mappingProperties != null) {
                value = mappingProperties.getProperty(key);
            }
        }
        return value;
    }

    private String getTypeHandler(Element element, String name) {
        String handler = getAttribute(element, name);
        /*
        if (handler != null && !mapping.isName(Mapping.TYPE_HANDLER_NAMESPACE, handler)) {
            throw new BeanIOConfigurationException("Unresolved type handler named '" + handler + "'");
        }
        */
        return handler;
    }
    
    private String doPropertySubstitution(String text) {
        try {
            return propertySubstitutionEnabled ?
                StringUtil.doPropertySubstitution(text, this) : text;
        }
        catch (IllegalArgumentException ex) {
            throw new BeanIOConfigurationException(ex.getMessage(), ex);
        }
    }
    
    private String getOptionalAttribute(Element element, String name) {
        Attr att = element.getAttributeNode(name);
        if (att == null) {
            return null;
        }
        else {
            return doPropertySubstitution(att.getTextContent());
        }
    }
    
    private boolean hasAttribute(Element element, String name) {
        return element.getAttributeNode(name) != null;
    }
    
    private String getAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if ("".equals(value)) {
            value = null;
        }
        return doPropertySubstitution(value);
    }
    
    private int getIntAttribute(Element element, String name, int defaultValue) {
        String text = getAttribute(element, name);
        if (text == null)
            return defaultValue;
        return Integer.parseInt(text);
    }

    private Character getCharacterAttribute(Element element, String name) {
        String text = getAttribute(element, name);
        if (text == null || text.length() == 0)
            return null;
        return text.charAt(0);
    }
    
    private Integer getIntegerAttribute(Element element, String name) {
        String text = getAttribute(element, name);
        if (text == null)
            return null;
        return Integer.parseInt(text);
    }

    private Integer getUnboundedIntegerAttribute(Element element, String name, int unboundedValue) {
        String text = getAttribute(element, name);
        if (text == null)
            return null;
        if ("unbounded".equals(text))
            return unboundedValue;
        return Integer.parseInt(text);
    }

    private boolean getBooleanAttribute(Element element, String name, boolean defaultValue) {
        String text = getAttribute(element, name);
        if (text == null)
            return defaultValue;
        return "true".equals(text) || "1".equals(text);
    }
    
    private static final class Include {
        private String template;
        private int offset = 0;
        
        public Include(String template, int offset) {
            this.template = template;
            this.offset = offset;
        }
        
        public String getTemplate() {
            return template;
        }
        
        public int getOffset() {
            return offset;
        }
    }
}
