/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.After;
import org.junit.Test;

import com.mirth.connect.model.PluginMetaData;
import com.mirth.connect.model.PropertyWriteProtection;
import com.mirth.connect.plugins.CheckedCompareAndSet;
import com.mirth.connect.plugins.ExpectedPropertyValue;
import com.mirth.connect.plugins.NoChange;
import com.mirth.connect.plugins.PluginPropertyCompletion;
import com.mirth.connect.plugins.PluginPropertyPreparers;
import com.mirth.connect.plugins.PluginPropertyRejectedException;
import com.mirth.connect.plugins.PluginPropertyWriteOutcome;
import com.mirth.connect.plugins.PluginPropertyWriteResult;
import com.mirth.connect.plugins.PreparedPluginProperties;
import com.mirth.connect.plugins.PropertyWriteContext;
import com.mirth.connect.server.ExtensionLoader;

public class DefaultExtensionControllerPropertySafetyTest {
    private PluginPropertyPreparers.PreparerRegistration registration;

    @After
    public void closeRegistration() {
        if (registration != null) {
            registration.close();
        }
    }

    @Test
    public void protectedWritePreparesCanonicalCasAndCompletesExactlyOnce() throws Exception {
        Fixture fixture = fixture(PropertyWriteProtection.PREPARED_ONLY);
        List<PluginPropertyCompletion> completions = new ArrayList<>();
        registration = PluginPropertyPreparers.register("protected", this,
                (incoming, merge, context) -> {
                    Properties canonical = new Properties();
                    canonical.setProperty("policy.v1", "canonical");
                    return new PreparedPluginProperties(canonical,
                            new CheckedCompareAndSet("policy.v1",
                                    ExpectedPropertyValue.present("before")),
                            (outcome, failure) -> completions.add(outcome));
                });
        registration.activate();
        when(fixture.configuration.compareAndSetPropertyAtomically(eq("protected"),
                eq("policy.v1"), any(ExpectedPropertyValue.class), eq("canonical")))
                        .thenReturn(AtomicPropertyWriteOutcome.COMMITTED);
        Properties incoming = new Properties();
        incoming.setProperty("policy.v1", "plaintext-input");

        PluginPropertyWriteResult result = fixture.controller.setPluginProperties("protected",
                incoming, false, PropertyWriteContext.pluginApi());
        incoming.setProperty("policy.v1", "later-mutation");

        assertEquals(PluginPropertyWriteOutcome.COMMITTED, result.getOutcome());
        assertEquals("canonical", result.getAppliedProperties().getProperty("policy.v1"));
        assertEquals(List.of(PluginPropertyCompletion.COMMITTED), completions);
        verify(fixture.configuration, never()).saveProperty(any(), any(), any());
        verify(fixture.configuration, never()).removePropertiesForGroup(any());
    }

    @Test
    public void protectedWriteWithoutLivePreparerReturnsUnavailableWithoutMutation()
            throws Exception {
        Fixture fixture = fixture(PropertyWriteProtection.PREPARED_ONLY);
        PluginPropertyWriteResult result = fixture.controller.setPluginProperties("protected",
                new Properties(), false, PropertyWriteContext.pluginApi());
        assertEquals(PluginPropertyWriteOutcome.PREPARER_UNAVAILABLE, result.getOutcome());
        assertNull(result.getAppliedProperties());
        verify(fixture.configuration, never()).saveProperty(any(), any(), any());
        verify(fixture.configuration, never()).removePropertiesForGroup(any());
        verify(fixture.configuration, never()).compareAndSetPropertyAtomically(
                any(), any(), any(), any());
    }

    @Test
    public void invalidCheckedShapeFailsBeforeMutationAndCompletesFailed() throws Exception {
        Fixture fixture = fixture(PropertyWriteProtection.PREPARED_ONLY);
        List<PluginPropertyCompletion> completions = new ArrayList<>();
        registration = PluginPropertyPreparers.register("protected", this,
                (incoming, merge, context) -> new PreparedPluginProperties(new Properties(),
                        new CheckedCompareAndSet("policy.v1", ExpectedPropertyValue.absent()),
                        (outcome, failure) -> {
                            assertEquals(outcome == PluginPropertyCompletion.FAILED,
                                    failure != null);
                            completions.add(outcome);
                        }));
        registration.activate();

        assertThrows(Exception.class, () -> fixture.controller.setPluginProperties("protected",
                new Properties(), false, PropertyWriteContext.pluginApi()));
        assertEquals(List.of(PluginPropertyCompletion.FAILED), completions);
        verify(fixture.configuration, never()).compareAndSetPropertyAtomically(
                any(), any(), any(), any());
    }

    @Test
    public void typedPreMutationRejectionEscapesWithoutMutationOrCompletion() throws Exception {
        Fixture fixture = fixture(PropertyWriteProtection.PREPARED_ONLY);
        registration = PluginPropertyPreparers.register("protected", this,
                (incoming, merge, context) -> {
                    throw new PluginPropertyRejectedException("revision_conflict", "revision",
                            "conflict", true);
                });
        registration.activate();

        PluginPropertyRejectedException failure = assertThrows(
                PluginPropertyRejectedException.class,
                () -> fixture.controller.setPluginProperties("protected", new Properties(),
                        false, PropertyWriteContext.pluginApi()));
        assertTrue(failure.isConflict());
        verify(fixture.configuration, never()).compareAndSetPropertyAtomically(
                any(), any(), any(), any());
    }

    @Test
    public void compatibilityMetadataRetainsLegacyReplaceBehavior() throws Exception {
        Fixture fixture = fixture(PropertyWriteProtection.COMPATIBILITY);
        Properties properties = new Properties();
        properties.setProperty("key", "value");
        PluginPropertyWriteResult result = fixture.controller.setPluginProperties("protected",
                properties, false, PropertyWriteContext.pluginApi());

        assertEquals(PluginPropertyWriteOutcome.LEGACY_APPLIED, result.getOutcome());
        verify(fixture.configuration).removePropertiesForGroup("protected");
        verify(fixture.configuration).saveProperty("protected", "key", "value");
    }

    @Test
    public void unknownNamespaceIsRejectedBeforeAnyMutation() throws Exception {
        ConfigurationController configuration = mock(ConfigurationController.class);
        ExtensionLoader loader = mock(ExtensionLoader.class);
        when(loader.getPluginMetaData()).thenReturn(Map.of());
        DefaultExtensionController controller = new DefaultExtensionController(configuration, loader);
        assertThrows(Exception.class, () -> controller.setPluginProperties("missing",
                new Properties(), false, PropertyWriteContext.pluginApi()));
        verify(configuration, never()).removePropertiesForGroup(any());
    }

    @Test
    public void baseControllerCannotRouteProtectedMetadataThroughLegacySetter() throws Exception {
        ExtensionController controller = mock(ExtensionController.class, CALLS_REAL_METHODS);
        PluginMetaData metadata = new PluginMetaData();
        metadata.setName("protected");
        metadata.setPropertyWriteProtection(PropertyWriteProtection.PREPARED_ONLY);
        when(controller.getPluginMetaData()).thenReturn(Map.of("protected", metadata));
        doThrow(new AssertionError("legacy setter reached"))
                .when(controller).setPluginProperties(eq("protected"), any(Properties.class),
                        eq(false));

        PluginPropertyWriteResult result = controller.setPluginProperties("protected",
                new Properties(), false, PropertyWriteContext.pluginApi());
        assertEquals(PluginPropertyWriteOutcome.PREPARER_UNAVAILABLE, result.getOutcome());
    }

    @Test
    public void baseControllerRejectsUnknownNamespaceBeforeLegacySetter() throws Exception {
        ExtensionController controller = mock(ExtensionController.class, CALLS_REAL_METHODS);
        when(controller.getPluginMetaData()).thenReturn(Map.of());
        doThrow(new AssertionError("legacy setter reached"))
                .when(controller).setPluginProperties(eq("missing"), any(Properties.class),
                        eq(false));

        assertThrows(com.mirth.connect.client.core.ControllerException.class,
                () -> controller.setPluginProperties("missing", new Properties(), false,
                        PropertyWriteContext.pluginApi()));
        verify(controller, never()).setPluginProperties(eq("missing"), any(Properties.class),
                eq(false));
    }

    private static Fixture fixture(PropertyWriteProtection protection) {
        ConfigurationController configuration = mock(ConfigurationController.class);
        ExtensionLoader loader = mock(ExtensionLoader.class);
        PluginMetaData metadata = new PluginMetaData();
        metadata.setName("protected");
        metadata.setPropertyWriteProtection(protection);
        when(loader.getPluginMetaData()).thenReturn(Map.of("protected", metadata));
        return new Fixture(new DefaultExtensionController(configuration, loader), configuration);
    }

    private static final class Fixture {
        private final DefaultExtensionController controller;
        private final ConfigurationController configuration;

        private Fixture(DefaultExtensionController controller,
                ConfigurationController configuration) {
            this.controller = controller;
            this.configuration = configuration;
        }
    }
}
