/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.server.controllers;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

import com.mirth.connect.client.core.BrandingConstants;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.mirth.connect.client.core.ControllerException;
import com.mirth.connect.client.core.VersionMismatchException;
import com.mirth.connect.model.ConnectorMetaData;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.model.MetaData;
import com.mirth.connect.model.PluginClass;
import com.mirth.connect.model.PluginClassCondition;
import com.mirth.connect.model.PluginMetaData;
import com.mirth.connect.model.PropertyWriteProtection;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.AuthorizationPlugin;
import com.mirth.connect.plugins.ChannelPlugin;
import com.mirth.connect.plugins.CodeTemplateServerPlugin;
import com.mirth.connect.plugins.DataTypeServerPlugin;
import com.mirth.connect.plugins.MultiFactorAuthenticationPlugin;
import com.mirth.connect.plugins.CheckedCompareAndSet;
import com.mirth.connect.plugins.NoChange;
import com.mirth.connect.plugins.PluginPropertyCompletion;
import com.mirth.connect.plugins.PluginPropertyPreparers;
import com.mirth.connect.plugins.PluginPropertyRejectedException;
import com.mirth.connect.plugins.PluginPropertyWriteException;
import com.mirth.connect.plugins.PluginPropertyWriteOutcome;
import com.mirth.connect.plugins.PluginPropertyWriteResult;
import com.mirth.connect.plugins.PreparedPluginProperties;
import com.mirth.connect.plugins.PreserveRejected;
import com.mirth.connect.plugins.PropertyPersistenceDirective;
import com.mirth.connect.plugins.PropertyWriteContext;
import com.mirth.connect.plugins.PropertyWriteOrigin;
import com.mirth.connect.plugins.ResourcePlugin;
import com.mirth.connect.plugins.ServerPlugin;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.plugins.TransmissionModeProvider;
import com.mirth.connect.server.ExtensionLoader;
import com.mirth.connect.server.extprops.ExtensionStatuses;
import com.mirth.connect.server.migration.Migrator;
import com.mirth.connect.server.util.DatabaseUtil;
import com.mirth.connect.server.util.ResourceUtil;
import com.mirth.connect.server.util.ServerUUIDGenerator;

public class DefaultExtensionController extends ExtensionController {
    private Logger logger = LogManager.getLogger(this.getClass());
    private ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
    private ConfigurationController configurationController;

    // these are plugins for specific extension points, keyed by plugin name
    // (not path)
    /*
     * A plugin class may implement several of the plugin type interfaces, in which case it is
     * registered once for each interface it implements. A Set holds a single entry per plugin,
     * so start() and stop() are invoked once per plugin rather than once per interface.
     * LinkedHashSet because initPlugins loads plugins in a deliberate order (by plugin weight)
     * and that order is preserved when they are started and stopped.
     */
    private Set<ServerPlugin> serverPlugins = new LinkedHashSet<ServerPlugin>();
    private Map<String, ServicePlugin> servicePlugins = new LinkedHashMap<String, ServicePlugin>();
    private Map<String, ChannelPlugin> channelPlugins = new LinkedHashMap<String, ChannelPlugin>();
    private Map<String, CodeTemplateServerPlugin> codeTemplateServerPlugins = new LinkedHashMap<String, CodeTemplateServerPlugin>();
    private Map<String, DataTypeServerPlugin> dataTypePlugins = new LinkedHashMap<String, DataTypeServerPlugin>();
    private Map<String, ResourcePlugin> resourcePlugins = new LinkedHashMap<String, ResourcePlugin>();
    private Map<String, TransmissionModeProvider> transmissionModeProviders = new LinkedHashMap<String, TransmissionModeProvider>();
    private MultiFactorAuthenticationPlugin multiFactorAuthenticationPlugin = null;
    private AuthorizationPlugin authorizationPlugin = null;
    private ExtensionLoader extensionLoader;
    private ExtensionStatuses extensionStatuses = ExtensionStatuses.getInstance();

    // singleton pattern
    private static ExtensionController instance = null;

    public static ExtensionController create() {
        synchronized (DefaultExtensionController.class) {
            if (instance == null) {
                instance = ExtensionLoader.getInstance().getControllerInstance(ExtensionController.class);

                if (instance == null) {
                    instance = new DefaultExtensionController();
                }
            }

            return instance;
        }
    }

    DefaultExtensionController() {
        this(ControllerFactory.getFactory().createConfigurationController(),
                ExtensionLoader.getInstance());
    }

    DefaultExtensionController(ConfigurationController configurationController,
            ExtensionLoader extensionLoader) {
        this.configurationController = configurationController;
        this.extensionLoader = extensionLoader;
    }

    @Override
    public void removePropertiesForUninstalledExtensions() {
        try {
            File uninstallFile = new File(getExtensionsPath(), EXTENSIONS_UNINSTALL_PROPERTIES_FILE);

            if (uninstallFile.exists()) {
                List<String> extensionPaths = FileUtils.readLines(uninstallFile);

                for (String extensionPath : extensionPaths) {
                    configurationController.removePropertiesForGroup(extensionPath);
                }

                // delete the uninstall file when we're done
                FileUtils.deleteQuietly(uninstallFile);
            }
        } catch (Exception e) {
            logger.error("Error removing properties for uninstalled extensions.", e);
        }
    }

    @Override
    public void setDefaultExtensionStatus() {
        for (MetaData metaData : getPluginMetaData().values()) {
            if (!extensionStatuses.containsKey(metaData.getName())) {
                extensionStatuses.setEnabled(metaData.getName(), true);
            }
        }

        for (MetaData metaData : getConnectorMetaData().values()) {
            if (!extensionStatuses.containsKey(metaData.getName())) {
                extensionStatuses.setEnabled(metaData.getName(), true);
            }
        }

        /*
         * Remove extensions from the extensionProperties if they are not in the pluginMetaDataMap
         * or connectorMetaDataMap
         */
        for (String key : extensionStatuses.keySet()) {
            if (!getPluginMetaData().containsKey(key) && !getConnectorMetaData().containsKey(key)) {
                extensionStatuses.remove(key);
            }
        }

        extensionStatuses.save();
    }

    @Override
    public void initPlugins() {
        // Order all the plugins by their weight before loading any of them.
        Map<String, String> pluginNameMap = new HashMap<String, String>();
        NavigableMap<Integer, List<String>> weightedPlugins = new TreeMap<Integer, List<String>>();
        for (PluginMetaData pmd : getPluginMetaData().values()) {
            if (isExtensionEnabled(pmd.getName())) {
                if (pmd.getServerClasses() != null) {
                    for (PluginClass pluginClass : pmd.getServerClasses()) {
                        String clazzName = pluginClass.getName();
                        int weight = pluginClass.getWeight();
                        String conditionClass = pluginClass.getConditionClass();

                        boolean accept = true;
                        if (StringUtils.isNotBlank(conditionClass)) {
                            try {
                                accept = ((PluginClassCondition) Class.forName(conditionClass).newInstance()).accept(pluginClass);
                            } catch (Exception e) {
                                logger.warn("Error instantiating plugin condition class \"" + conditionClass + "\".");
                            }
                        }

                        if (accept) {
                            pluginNameMap.put(clazzName, pmd.getName());

                            List<String> classList = weightedPlugins.get(weight);
                            if (classList == null) {
                                classList = new ArrayList<String>();
                                weightedPlugins.put(weight, classList);
                            }

                            classList.add(clazzName);
                        }
                    }
                }
            } else {
                logger.warn("Plugin \"" + pmd.getName() + "\" is not enabled.");
            }
        }

        // Load the plugins in order of their weight
        for (List<String> classList : weightedPlugins.descendingMap().values()) {
            for (String clazzName : classList) {
                String pluginName = pluginNameMap.get(clazzName);
                ServerPlugin serverPlugin = null;

                try {
                    serverPlugin = (ServerPlugin) Class.forName(clazzName).newInstance();

                    if (serverPlugin instanceof ServicePlugin) {
                        ServicePlugin servicePlugin = (ServicePlugin) serverPlugin;
                        /*
                         * load any properties that may currently be in the database
                         */
                        PluginMetaData pluginMetaData = requirePluginMetaData(pluginName);
                        Properties currentProperties = pluginMetaData.getPropertyWriteProtection()
                                == PropertyWriteProtection.PREPARED_ONLY
                                ? toProperties(configurationController
                                        .readPropertiesForGroupChecked(pluginName))
                                : getPluginProperties(pluginName);
                        /* get the default properties for the plugin */
                        Properties defaultProperties = servicePlugin.getDefaultProperties();

                        /*
                         * if there are any properties that not currently set, set them to the the
                         * default
                         */
                        for (Object key : defaultProperties.keySet()) {
                            if (!currentProperties.containsKey(key)) {
                                currentProperties.put(key, defaultProperties.get(key));
                            }
                        }

                        /* save the properties to the database */
                        PluginPropertyWriteResult writeResult = setPluginProperties(pluginName,
                                currentProperties, false, PropertyWriteContext.initialization());
                        Properties appliedProperties;
                        if (writeResult.getOutcome() == PluginPropertyWriteOutcome.PRESERVED_REJECTED) {
                            appliedProperties = new Properties();
                        } else if (isSuccessful(writeResult.getOutcome())) {
                            appliedProperties = writeResult.getAppliedProperties();
                        } else {
                            throw new PluginPropertyWriteException(writeResult.getOutcome());
                        }

                        /*
                         * initialize the plugin with those properties and add it to the list of
                         * loaded plugins
                         */
                        servicePlugin.init(appliedProperties);
                        servicePlugins.put(servicePlugin.getPluginPointName(), servicePlugin);
                        serverPlugins.add(servicePlugin);
                        logger.debug("sucessfully loaded server plugin: " + serverPlugin.getPluginPointName());
                    }

                    if (serverPlugin instanceof ChannelPlugin) {
                        ChannelPlugin channelPlugin = (ChannelPlugin) serverPlugin;
                        channelPlugins.put(channelPlugin.getPluginPointName(), channelPlugin);
                        serverPlugins.add(channelPlugin);
                        logger.debug("sucessfully loaded server channel plugin: " + serverPlugin.getPluginPointName());
                    }

                    if (serverPlugin instanceof CodeTemplateServerPlugin) {
                        CodeTemplateServerPlugin codeTemplateServerPlugin = (CodeTemplateServerPlugin) serverPlugin;
                        codeTemplateServerPlugins.put(codeTemplateServerPlugin.getPluginPointName(), codeTemplateServerPlugin);
                        serverPlugins.add(codeTemplateServerPlugin);
                        logger.debug("sucessfully loaded server code template plugin: " + serverPlugin.getPluginPointName());
                    }

                    if (serverPlugin instanceof DataTypeServerPlugin) {
                        DataTypeServerPlugin dataTypePlugin = (DataTypeServerPlugin) serverPlugin;
                        dataTypePlugins.put(dataTypePlugin.getPluginPointName(), dataTypePlugin);
                        serverPlugins.add(dataTypePlugin);
                        logger.debug("sucessfully loaded server data type plugin: " + serverPlugin.getPluginPointName());
                    }

                    if (serverPlugin instanceof ResourcePlugin) {
                        ResourcePlugin resourcePlugin = (ResourcePlugin) serverPlugin;
                        resourcePlugins.put(resourcePlugin.getPluginPointName(), resourcePlugin);
                        serverPlugins.add(resourcePlugin);
                        logger.debug("Successfully loaded resource plugin: " + resourcePlugin.getPluginPointName());
                    }

                    if (serverPlugin instanceof TransmissionModeProvider) {
                        TransmissionModeProvider transmissionModeProvider = (TransmissionModeProvider) serverPlugin;
                        transmissionModeProviders.put(transmissionModeProvider.getPluginPointName(), transmissionModeProvider);
                        serverPlugins.add(transmissionModeProvider);
                        logger.debug("Successfully loaded transmission mode provider plugin: " + transmissionModeProvider.getPluginPointName());
                    }

                    if (serverPlugin instanceof AuthorizationPlugin) {
                        AuthorizationPlugin authorizationPlugin = (AuthorizationPlugin) serverPlugin;

                        if (this.authorizationPlugin != null) {
                            throw new Exception("Multiple Authorization Plugins are not permitted.");
                        }

                        this.authorizationPlugin = authorizationPlugin;
                        serverPlugins.add(authorizationPlugin);
                        logger.debug("sucessfully loaded server authorization plugin: " + serverPlugin.getPluginPointName());
                    }

                    if (serverPlugin instanceof MultiFactorAuthenticationPlugin) {
                        MultiFactorAuthenticationPlugin multiFactorAuthenticationPlugin = (MultiFactorAuthenticationPlugin) serverPlugin;

                        if (this.multiFactorAuthenticationPlugin != null) {
                            throw new Exception("Multiple Multi-Factor Authentication Plugins are not permitted.");
                        }

                        this.multiFactorAuthenticationPlugin = multiFactorAuthenticationPlugin;
                        serverPlugins.add(multiFactorAuthenticationPlugin);
                        logger.debug("sucessfully loaded server multi-factor authentication plugin: " + serverPlugin.getPluginPointName());
                    }
                } catch (Throwable e) {
                    if (serverPlugin != null) {
                        try {
                            serverPlugin.stop();
                        } catch (Throwable stopFailure) {
                            e.addSuppressed(stopFailure);
                        }
                    }
                    if (e instanceof VirtualMachineError) {
                        throw (VirtualMachineError) e;
                    }
                    if (e instanceof ThreadDeath) {
                        throw (ThreadDeath) e;
                    }
                    logger.error("Error instantiating plugin: " + pluginName, e);
                }
            }
        }
    }

    /* These are the maps for the different types of plugins */
    /* ********************************************************************** */

    @Override
    public Map<String, ServicePlugin> getServicePlugins() {
        return servicePlugins;
    }

    @Override
    public Map<String, ChannelPlugin> getChannelPlugins() {
        return channelPlugins;
    }

    @Override
    public Map<String, CodeTemplateServerPlugin> getCodeTemplateServerPlugins() {
        return codeTemplateServerPlugins;
    }

    @Override
    public Map<String, DataTypeServerPlugin> getDataTypePlugins() {
        return dataTypePlugins;
    }

    @Override
    public Map<String, ResourcePlugin> getResourcePlugins() {
        return resourcePlugins;
    }

    @Override
    public Map<String, TransmissionModeProvider> getTransmissionModeProviders() {
        return transmissionModeProviders;
    }

    @Override
    public AuthorizationPlugin getAuthorizationPlugin() {
        return authorizationPlugin;
    }

    @Override
    public MultiFactorAuthenticationPlugin getMultiFactorAuthenticationPlugin() {
        return multiFactorAuthenticationPlugin;
    }

    /* ********************************************************************** */

    @Override
    public void setExtensionEnabled(String extensionName, boolean enabled) throws ControllerException {
        extensionStatuses.setEnabled(extensionName, enabled);
        extensionStatuses.save();
    }

    @Override
    public boolean isExtensionEnabled(String extensionName) {
        return extensionStatuses.isEnabled(extensionName);
    }

    @Override
    public Set<String> getDisabledExtensions() {
        Set<String> disabledExtensions = new LinkedHashSet<String>();

        for (MetaData metaData : getPluginMetaData().values()) {
            if (!isExtensionEnabled(metaData.getName())) {
                disabledExtensions.add(metaData.getName());
            }
        }

        for (MetaData metaData : getConnectorMetaData().values()) {
            if (!isExtensionEnabled(metaData.getName())) {
                disabledExtensions.add(metaData.getName());
            }
        }

        return disabledExtensions;
    }

    @Override
    public void startPlugins() {
        for (ServerPlugin serverPlugin : serverPlugins) {
            serverPlugin.start();
        }

        // Get all of the server plugin extension permissions and add those to
        // the authorization controller.
        AuthorizationController authorizationController = ControllerFactory.getFactory().createAuthorizationController();

        for (ServicePlugin plugin : servicePlugins.values()) {
            if (plugin.getExtensionPermissions() != null) {
                for (ExtensionPermission extensionPermission : plugin.getExtensionPermissions()) {
                    authorizationController.addExtensionPermission(extensionPermission);
                }
            }
        }
    }

    @Override
    public void stopPlugins() {
        for (ServerPlugin serverPlugin : serverPlugins) {
            serverPlugin.stop();
        }
    }

    @Override
    public void updatePluginProperties(String name, Properties properties) {
        ServicePlugin servicePlugin = servicePlugins.get(name);

        if (servicePlugin != null) {
            servicePlugin.update(properties);
        } else {
            logger.error("Error setting properties for service plugin that has not been loaded: name=" + name);
        }
    }

    @Override
    public InstallationResult extractExtension(InputStream inputStream) {
        Throwable cause = null;
        Set<MetaData> metaDataSet = new HashSet<MetaData>();

        File installTempDir = new File(ExtensionController.getExtensionsPath(), "install_temp");

        if (!installTempDir.exists()) {
            installTempDir.mkdir();
        }

        File tempFile = null;
        FileOutputStream tempFileOutputStream = null;
        ZipFile zipFile = null;

        try {
            /*
             * create a new temp file (in the install temp dir) to store the zip file contents
             */
            tempFile = File.createTempFile(ServerUUIDGenerator.getUUID(), ".zip", installTempDir);
            // write the contents of the multipart fileitem to the temp file
            try {
                tempFileOutputStream = new FileOutputStream(tempFile);
                IOUtils.copy(inputStream, tempFileOutputStream);
            } finally {
                ResourceUtil.closeResourceQuietly(tempFileOutputStream);
            }

            // create a new zip file from the temp file
            zipFile = new ZipFile(tempFile);
            // get a list of all of the entries in the zip file
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.endsWith("plugin.xml") || entryName.endsWith("destination.xml") || entryName.endsWith("source.xml")) {
                    // parse the extension metadata xml file
                    MetaData extensionMetaData = serializer.deserialize(IOUtils.toString(zipFile.getInputStream(entry)), MetaData.class);
                    metaDataSet.add(extensionMetaData);

                    if (!extensionLoader.isExtensionCompatible(extensionMetaData)) {
                        if (cause == null) {
                            cause = new VersionMismatchException(String.format("Extension \"%s\" is not compatible with this version of %s.", entry.getName(), BrandingConstants.PRODUCT_NAME));
                        }
                    }
                }
            }

            if (cause == null) {
                // reset the entries and extract
                entries = zipFile.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    extractZipEntry(entry, installTempDir, zipFile);
                }
            }
        } catch (Throwable t) {
            cause = new ControllerException("Error extracting extension. " + t.toString(), t);
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Exception e) {
                    cause = new ControllerException(e);
                }
            }

            // delete the temp file since it is no longer needed
            FileUtils.deleteQuietly(tempFile);
        }

        return new InstallationResult(cause, metaDataSet);
    }

    /**
     * Adds the specified plugin path to a list of plugins that should be deleted on next server
     * startup. Also deletes the schema version property from the database. If this function fails
     * to add the extension path to the uninstall file, it will still continue to remove add the
     * database uninstall scripts, and the folder must be deleted manually.
     * 
     */
    @Override
    public void prepareExtensionForUninstallation(String pluginPath) throws ControllerException {
        addExtensionToUninstallFile(pluginPath);

        for (PluginMetaData plugin : getPluginMetaData().values()) {
            if (plugin.getPath().equals(pluginPath)) {
                addExtensionToUninstallPropertiesFile(plugin.getName());

                if (plugin.getMigratorClass() != null) {
                    try {
                        Migrator migrator = (Migrator) Class.forName(plugin.getMigratorClass()).newInstance();
                        migrator.setDatabaseType(ConfigurationController.getInstance().getDatabaseType());
                        migrator.setDefaultScriptPath("extensions/" + plugin.getPath());
                        appendToUninstallScript(migrator.getUninstallStatements());
                    } catch (Exception e) {
                        logger.error("Failed to retrieve uninstall database statements for plugin: " + pluginPath, e);
                    }
                }
            }
        }
    }

    /*
     * Parses the uninstallation script and returns a list of statements.
     */
    private List<String> parseUninstallScript(String script) {
        List<String> scriptList = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean blankLine = false;
        Scanner scanner = new Scanner(script);

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();

            if (StringUtils.isNotBlank(line)) {
                sb.append(line + " ");
            } else {
                blankLine = true;
            }

            if (blankLine || !scanner.hasNextLine()) {
                scriptList.add(sb.toString().trim());
                blankLine = false;
                sb.delete(0, sb.length());
            }
        }

        return scriptList;
    }

    /*
     * append the extension path name to a list of extensions that should be deleted on next startup
     * by MirthLauncher
     */
    private void addExtensionToUninstallFile(String pluginPath) {
        File uninstallFile = new File(getExtensionsPath(), EXTENSIONS_UNINSTALL_FILE);
        FileWriter writer = null;

        try {
            writer = new FileWriter(uninstallFile, true);
            writer.write(pluginPath + System.getProperty("line.separator"));
        } catch (IOException e) {
            logger.error("Error adding extension to uninstall file: " + pluginPath, e);
        } finally {
            ResourceUtil.closeResourceQuietly(writer);
        }
    }

    private void addExtensionToUninstallPropertiesFile(String pluginName) {
        File uninstallFile = new File(getExtensionsPath(), EXTENSIONS_UNINSTALL_PROPERTIES_FILE);
        FileWriter writer = null;

        try {
            writer = new FileWriter(uninstallFile, true);
            writer.write(pluginName + System.getProperty("line.separator"));
        } catch (IOException e) {
            logger.error("Error adding extension to uninstall properties file: " + pluginName, e);
        } finally {
            ResourceUtil.closeResourceQuietly(writer);
        }
    }

    private String getUninstallScriptForCurrentDatabase(String pluginSqlScripts) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    	Document document = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(pluginSqlScripts)));
        Element uninstallElement = (Element) document.getElementsByTagName("uninstall").item(0);
        String databaseType = ControllerFactory.getFactory().createConfigurationController().getDatabaseType();
        NodeList scriptNodes = uninstallElement.getElementsByTagName("script");
        String script = null;

        for (int i = 0; i < scriptNodes.getLength(); i++) {
            Node scriptNode = scriptNodes.item(i);
            Node scriptType = scriptNode.getAttributes().getNamedItem("type");
            String[] databaseTypes = scriptType.getTextContent().split(",");

            for (int j = 0; j < databaseTypes.length; j++) {
                if (databaseTypes[j].equals("all") || databaseTypes[j].equals(databaseType)) {
                    script = scriptNode.getTextContent().trim();
                }
            }
        }

        return script;
    }

    @Override
    public void setPluginProperties(String pluginName, Properties properties, boolean mergeProperties) throws ControllerException {
        PluginPropertyWriteResult result = setPluginProperties(pluginName, properties,
                mergeProperties, PropertyWriteContext.pluginApi());
        if (!isSuccessful(result.getOutcome())) {
            throw new PluginPropertyWriteException(result.getOutcome());
        }
    }
    @Override
    public PluginPropertyWriteResult setPluginProperties(String pluginName, Properties properties,
            boolean mergeProperties, PropertyWriteContext context) throws ControllerException {
        PluginMetaData metadata = requirePluginMetaData(pluginName);
        Properties incoming = copyStringProperties(properties);

        if (metadata.getPropertyWriteProtection() != PropertyWriteProtection.PREPARED_ONLY) {
            applyLegacyProperties(pluginName, incoming, mergeProperties);
            return PluginPropertyWriteResult.withProperties(
                    PluginPropertyWriteOutcome.LEGACY_APPLIED, incoming);
        }

        // Allocate durability evidence before a prepared transaction can transfer ownership.
        CheckedPropertyWriteReceipt receipt = new CheckedPropertyWriteReceipt();
        // Initialize all completion constants before any prepared owner exists.
        PluginPropertyCompletion failedCompletion = PluginPropertyCompletion.FAILED;
        PreparedPluginProperties prepared = null;
        boolean completionDelivered = false;
        try {
            prepared = PluginPropertyPreparers.prepare(pluginName, incoming, mergeProperties, context);
            Properties canonical = prepared.getCanonicalProperties();
            PropertyPersistenceDirective directive = prepared.getDirective();

            if (directive instanceof CheckedCompareAndSet) {
                CheckedCompareAndSet checked = (CheckedCompareAndSet) directive;
                validateCheckedCanonical(canonical, checked);
                AtomicPropertyWriteOutcome atomic = configurationController
                        .compareAndSetPropertyAtomically(pluginName, checked.getPropertyName(),
                                checked.getExpected(), canonical.getProperty(checked.getPropertyName()), receipt);
                PluginPropertyWriteOutcome outcome = mapAtomicOutcome(atomic);
                if (completionFromReceipt(receipt) != mapCompletion(outcome))
                    throw new ControllerException("checked_property_write_receipt_mismatch");
                completionDelivered = true;
                complete(prepared, mapCompletion(outcome), null);
                return outcome == PluginPropertyWriteOutcome.COMMITTED
                        ? PluginPropertyWriteResult.withProperties(outcome, canonical)
                        : PluginPropertyWriteResult.withoutProperties(outcome);
            }
            if (directive instanceof NoChange) {
                completionDelivered = true;
                complete(prepared, PluginPropertyCompletion.NO_CHANGE, null);
                return PluginPropertyWriteResult.withProperties(
                        PluginPropertyWriteOutcome.NO_CHANGE, canonical);
            }
            if (directive instanceof PreserveRejected) {
                if (context.getOrigin() != PropertyWriteOrigin.INITIALIZATION || mergeProperties) {
                    throw new ControllerException("preserve_rejected_context_invalid");
                }
                completionDelivered = true;
                complete(prepared, PluginPropertyCompletion.PRESERVED_REJECTED, null);
                return PluginPropertyWriteResult.withoutProperties(
                        PluginPropertyWriteOutcome.PRESERVED_REJECTED);
            }
            throw new ControllerException("unsupported_property_persistence_directive");
        } catch (Exception | Error failure) {
            Throwable selected = failure;
            if (prepared != null && !completionDelivered) {
                PluginPropertyCompletion outcome = completionFromReceipt(receipt);
                completionDelivered = true;
                try {
                    complete(prepared, outcome, outcome == failedCompletion ? failure : null);
                } catch (Exception | Error completionFailure) {
                    selected = combinePropertyFailure(selected, completionFailure);
                }
            }
            if (selected instanceof Error error) throw error;
            if (prepared == null && selected instanceof PluginPropertyWriteException write)
                return PluginPropertyWriteResult.withoutProperties(write.getOutcome());
            if (selected instanceof ControllerException controller) throw controller;
            throw new ControllerException("plugin_property_preparation_failed", selected);
        }
    }

    private void applyLegacyProperties(String pluginName, Properties properties,
            boolean mergeProperties) {
        if (!mergeProperties) {
            configurationController.removePropertiesForGroup(pluginName);
        }

        for (Object name : properties.keySet()) {
            configurationController.saveProperty(pluginName, (String) name, (String) properties.get(name));
        }
    }

    private PluginMetaData requirePluginMetaData(String pluginName) throws ControllerException {
        PluginMetaData metadata = getPluginMetaData().get(pluginName);
        if (metadata == null) {
            throw new ControllerException("unknown_plugin_property_namespace");
        }
        return metadata;
    }

    private static Properties copyStringProperties(Properties properties) throws ControllerException {
        if (properties == null) {
            throw new ControllerException("plugin_properties_required");
        }
        Properties copy = new Properties();
        for (Object key : properties.keySet()) {
            Object value = properties.get(key);
            if (!(key instanceof String) || !(value instanceof String)) {
                throw new ControllerException("plugin_properties_must_be_strings");
            }
            copy.setProperty((String) key, (String) value);
        }
        return copy;
    }

    private static void validateCheckedCanonical(Properties canonical, CheckedCompareAndSet checked)
            throws ControllerException {
        if (canonical.size() != 1 || !canonical.containsKey(checked.getPropertyName())
                || canonical.getProperty(checked.getPropertyName()) == null) {
            throw new ControllerException("checked_property_canonical_shape_invalid");
        }
    }

    private static PluginPropertyCompletion completionFromReceipt(CheckedPropertyWriteReceipt receipt) {
        CheckedPropertyWriteReceipt.State state = receipt.state();
        if (state == CheckedPropertyWriteReceipt.State.COMMITTED) return PluginPropertyCompletion.COMMITTED;
        if (state == CheckedPropertyWriteReceipt.State.CONFLICT) return PluginPropertyCompletion.CONFLICT;
        if (state == CheckedPropertyWriteReceipt.State.OUTCOME_UNKNOWN) return PluginPropertyCompletion.OUTCOME_UNKNOWN;
        return PluginPropertyCompletion.FAILED;
    }

    private static Throwable combinePropertyFailure(Throwable first, Throwable next) {
        if (first == null || first == next) return next;
        boolean firstFatal = first instanceof VirtualMachineError || first instanceof ThreadDeath;
        boolean nextFatal = next instanceof VirtualMachineError || next instanceof ThreadDeath;
        Throwable primary = !firstFatal && nextFatal ? next : first;
        Throwable secondary = primary == first ? next : first;
        try { primary.addSuppressed(secondary); }
        catch (RuntimeException | Error metadata) {
            if (!firstFatal && !nextFatal
                    && (metadata instanceof VirtualMachineError || metadata instanceof ThreadDeath)) return metadata;
        }
        return primary;
    }

    private static PluginPropertyWriteOutcome mapAtomicOutcome(AtomicPropertyWriteOutcome outcome) {
        switch (outcome) {
            case COMMITTED:
                return PluginPropertyWriteOutcome.COMMITTED;
            case CONFLICT:
                return PluginPropertyWriteOutcome.CONFLICT;
            case OUTCOME_UNKNOWN:
                return PluginPropertyWriteOutcome.OUTCOME_UNKNOWN;
            default:
                throw new IllegalArgumentException("unknown atomic outcome");
        }
    }

    private static PluginPropertyCompletion mapCompletion(PluginPropertyWriteOutcome outcome) {
        switch (outcome) {
            case COMMITTED:
                return PluginPropertyCompletion.COMMITTED;
            case CONFLICT:
                return PluginPropertyCompletion.CONFLICT;
            case OUTCOME_UNKNOWN:
                return PluginPropertyCompletion.OUTCOME_UNKNOWN;
            default:
                throw new IllegalArgumentException("outcome has no completion mapping");
        }
    }

    private static void complete(PreparedPluginProperties prepared,
            PluginPropertyCompletion outcome, Throwable failure) throws ControllerException {
        if ((outcome == PluginPropertyCompletion.FAILED) != (failure != null)) {
            throw new ControllerException("property_completion_failure_contract_invalid");
        }
        try {
            prepared.getCompletion().complete(outcome, failure);
        } catch (Exception e) {
            throw new ControllerException("property_completion_failed", e);
        }
    }

    private static boolean isSuccessful(PluginPropertyWriteOutcome outcome) {
        return outcome == PluginPropertyWriteOutcome.COMMITTED
                || outcome == PluginPropertyWriteOutcome.NO_CHANGE
                || outcome == PluginPropertyWriteOutcome.LEGACY_APPLIED;
    }
    @Override
    public Properties getPluginProperties(String pluginName, Set<String> propertyKeys) throws ControllerException {
        PluginMetaData metadata = requirePluginMetaData(pluginName);
        if (metadata.getPropertyWriteProtection() == PropertyWriteProtection.PREPARED_ONLY) {
            Properties properties = toProperties(
                    configurationController.readPropertiesForGroupChecked(pluginName));
            if (propertyKeys == null || propertyKeys.isEmpty()) {
                return properties;
            }
            Properties filtered = new Properties();
            for (String key : propertyKeys) {
                String value = properties.getProperty(key);
                if (value != null) {
                    filtered.setProperty(key, value);
                }
            }
            return filtered;
        }
        return configurationController.getPropertiesForGroup(pluginName, propertyKeys);
    }

    private static Properties toProperties(Map<String, String> values) {
        Properties properties = new Properties();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    @Override
    public Map<String, ConnectorMetaData> getConnectorMetaData() {
        return extensionLoader.getConnectorMetaData();
    }

    @Override
    public Map<String, PluginMetaData> getPluginMetaData() {
        return extensionLoader.getPluginMetaData();
    }

    @Override
    public ConnectorMetaData getConnectorMetaDataByProtocol(String protocol) {
        return extensionLoader.getConnectorProtocols().get(protocol);
    }

    @Override
    public ConnectorMetaData getConnectorMetaDataByTransportName(String transportName) {
        return extensionLoader.getConnectorMetaData().get(transportName);
    }

    @Override
    public Map<String, MetaData> getInvalidMetaData() {
        return extensionLoader.getInvalidMetaData();
    }

    /**
     * Executes the script that removes that database tables for plugins that are marked for
     * removal. The actual removal of the plugin directory happens in MirthLauncher.java, before
     * they can be added to the server classpath.
     * 
     */
    @Override
    public void uninstallExtensions() {
        try {
            DatabaseUtil.executeScript(readUninstallScript(), true);
        } catch (Exception e) {
            logger.error("Error uninstalling extensions.", e);
        }

        // delete the uninstall scripts file
        FileUtils.deleteQuietly(new File(getExtensionsPath(), EXTENSIONS_UNINSTALL_SCRIPTS_FILE));
    }

    private void appendToUninstallScript(List<String> uninstallStatements) throws IOException {
        if (uninstallStatements != null) {
            List<String> uninstallScripts = readUninstallScript();
            uninstallScripts.addAll(uninstallStatements);
            File uninstallScriptsFile = new File(getExtensionsPath(), EXTENSIONS_UNINSTALL_SCRIPTS_FILE);
            FileUtils.writeStringToFile(uninstallScriptsFile, serializer.serialize(uninstallScripts));
        }
    }

    /*
     * This MUST return an empty list if there is no uninstall file.
     */
    @SuppressWarnings("unchecked")
    private List<String> readUninstallScript() throws IOException {
        File uninstallScriptsFile = new File(getExtensionsPath(), EXTENSIONS_UNINSTALL_SCRIPTS_FILE);
        List<String> scripts = new ArrayList<String>();

        if (uninstallScriptsFile.exists()) {
            scripts = serializer.deserializeList(FileUtils.readFileToString(uninstallScriptsFile), String.class);
        }

        return scripts;
    }

    public List<String> getClientLibraries() {
        List<String> clientLibFilenames = new ArrayList<String>();
        File clientLibDir = new File("client-lib");

        if (!clientLibDir.exists() || !clientLibDir.isDirectory()) {
            clientLibDir = new File("build/client-lib");
        }

        if (clientLibDir.exists() && clientLibDir.isDirectory()) {
            Collection<File> clientLibs = FileUtils.listFiles(clientLibDir, new SuffixFileFilter(".jar"), FileFilterUtils.falseFileFilter());

            for (File clientLib : clientLibs) {
                clientLibFilenames.add(FilenameUtils.getName(clientLib.getName()));
            }
        } else {
            logger.error("Could not find client-lib directory: " + clientLibDir.getAbsolutePath());
        }

        return clientLibFilenames;
    }

    public List<ServerPlugin> getServerPlugins() {
        // Copied into a List so the ExtensionController signature stays unchanged for extensions.
        return new ArrayList<ServerPlugin>(serverPlugins);
    }

    void extractZipEntry(ZipEntry entry, File installTempDir, ZipFile zipFile) throws IOException {
        String canonicalDestinationDirPath = installTempDir.getCanonicalPath();
        File destinationfile = new File(installTempDir, entry.getName());
        String canonicalDestinationFile = destinationfile.getCanonicalPath();

        if (!canonicalDestinationFile.startsWith(canonicalDestinationDirPath + File.separator)) {
            throw new ZipException("Zip file is attempting to traverse out of base directory");
        }

        if (entry.isDirectory()) {
            /*
             * assume directories are stored parents first then children.
             * 
             * TODO: this is not robust, just for demonstration purposes.
             */
            File directory = new File(installTempDir, entry.getName());
            directory.mkdir();
        } else {
            // otherwise, write the file out to the install temp dir
            InputStream zipInputStream = null;
            FileOutputStream fileOutputStream = null;
            OutputStream outputStream = null;
            try {
                zipInputStream = zipFile.getInputStream(entry);
                fileOutputStream = new FileOutputStream(new File(installTempDir, entry.getName()));
                outputStream = new BufferedOutputStream(fileOutputStream);
                IOUtils.copy(zipInputStream, outputStream);
            } finally {
                ResourceUtil.closeResourceQuietly(outputStream);
                ResourceUtil.closeResourceQuietly(fileOutputStream);
                ResourceUtil.closeResourceQuietly(zipInputStream);
            }
        }
    }
}
