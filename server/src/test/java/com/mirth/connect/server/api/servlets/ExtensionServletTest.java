/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 *
 * http://www.mirthcorp.com
 *
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.server.api.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.core.SecurityContext;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.mirth.connect.client.core.ControllerException;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.client.core.api.Param;
import com.mirth.connect.client.core.api.servlets.ExtensionServletInterface;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.model.ServerEvent.Outcome;
import com.mirth.connect.server.api.providers.MirthResourceInvocationHandlerProvider;
import com.mirth.connect.server.controllers.AuthorizationController;
import com.mirth.connect.server.controllers.ChannelAuthorizer;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.controllers.ExtensionController;
import com.mirth.connect.server.controllers.UserController;

public class ExtensionServletTest {

    private static final String EXTENSION_NAME = "test-extension";
    private static final String SECRET_KEY = "secret.header";
    private static final String SECRET_VALUE = "phi-canary-value";

    @Test
    public void setPluginPropertiesExcludesRequestBodyFromAcceptedAndRejectedAudits() throws Throwable {
        Method method = ExtensionServletInterface.class.getMethod("setPluginProperties", String.class, Properties.class, boolean.class);
        Param propertiesParam = findParam(method.getParameterAnnotations()[1]);
        assertEquals("properties", propertiesParam.value());
        assertTrue("The invocation-handler audit path must exclude the request body", propertiesParam.excludeFromAudit());

        ControllerFactory controllerFactory = mock(ControllerFactory.class);
        ConfigurationController configurationController = mock(ConfigurationController.class);
        ChannelController channelController = mock(ChannelController.class);
        EventController eventController = mock(EventController.class);
        ExtensionController extensionController = mock(ExtensionController.class);
        UserController userController = mock(UserController.class);
        when(configurationController.getServerId()).thenReturn("test-server");
        when(controllerFactory.createConfigurationController()).thenReturn(configurationController);
        when(controllerFactory.createChannelController()).thenReturn(channelController);
        when(controllerFactory.createEventController()).thenReturn(eventController);
        when(controllerFactory.createExtensionController()).thenReturn(extensionController);
        when(controllerFactory.createUserController()).thenReturn(userController);

        TestAuthorizationController authorizationController = new TestAuthorizationController(controllerFactory);
        when(controllerFactory.createAuthorizationController()).thenReturn(authorizationController);
        doAnswer(invocation -> {
            eventController.insertEvent(invocation.getArgument(0));
            return null;
        }).when(eventController).dispatchEvent(any(ServerEvent.class));

        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("user")).thenReturn("1");
        when(session.getAttribute("authorized")).thenReturn(Boolean.TRUE);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getSession()).thenReturn(session);
        SecurityContext securityContext = mock(SecurityContext.class);
        InvocationHandler invocationHandler = new MirthResourceInvocationHandlerProvider().create(null);

        Properties submitted = new Properties();
        submitted.setProperty(SECRET_KEY, SECRET_VALUE);

        try (MockedStatic<ControllerFactory> factories = mockStatic(ControllerFactory.class)) {
            factories.when(ControllerFactory::getFactory).thenReturn(controllerFactory);

            authorizationController.setAuthorized(true);
            invocationHandler.invoke(new ExtensionServlet(request, securityContext), method, new Object[] {
                    EXTENSION_NAME, submitted, false });
            verify(extensionController).setPluginProperties(EXTENSION_NAME, submitted, false);
            verify(extensionController).updatePluginProperties(EXTENSION_NAME, submitted);

            clearInvocations(extensionController);
            authorizationController.setAuthorized(false);
            try {
                invocationHandler.invoke(new ExtensionServlet(request, securityContext), method, new Object[] {
                        EXTENSION_NAME, submitted, true });
                fail("Expected the rejected write to return forbidden");
            } catch (InvocationTargetException e) {
                assertTrue(e.getCause() instanceof MirthApiException);
                assertEquals(javax.ws.rs.core.Response.Status.FORBIDDEN.getStatusCode(), ((MirthApiException) e.getCause()).getResponse().getStatus());
            }
            verifyNoInteractions(extensionController);
        }

        assertEquals(2, authorizationController.getParameterMaps().size());
        for (Map<String, Object> parameterMap : authorizationController.getParameterMaps()) {
            assertEquals(1, parameterMap.size());
            assertEquals(EXTENSION_NAME, parameterMap.get("extensionName"));
            assertFalse(parameterMap.containsKey("properties"));
            assertFalse(parameterMap.toString().contains(SECRET_KEY));
            assertFalse(parameterMap.toString().contains(SECRET_VALUE));
        }

        ArgumentCaptor<ServerEvent> persistedEvents = ArgumentCaptor.forClass(ServerEvent.class);
        verify(eventController, times(2)).insertEvent(persistedEvents.capture());
        assertEquals(Outcome.SUCCESS, persistedEvents.getAllValues().get(0).getOutcome());
        assertEquals(Outcome.FAILURE, persistedEvents.getAllValues().get(1).getOutcome());
        for (ServerEvent persistedEvent : persistedEvents.getAllValues()) {
            assertEquals(1, persistedEvent.getAttributes().size());
            assertTrue(persistedEvent.getAttributes().containsKey("extensionName"));
            assertFalse(persistedEvent.getAttributes().containsKey("properties"));
            assertFalse(persistedEvent.getAttributes().toString().contains(SECRET_KEY));
            assertFalse(persistedEvent.getAttributes().toString().contains(SECRET_VALUE));
        }
    }

    private Param findParam(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Param) {
                return (Param) annotation;
            }
        }
        throw new AssertionError("Properties parameter does not declare @Param");
    }

    private static class TestAuthorizationController extends AuthorizationController {
        private final List<Map<String, Object>> parameterMaps = new ArrayList<Map<String, Object>>();
        private boolean authorized;

        private TestAuthorizationController(ControllerFactory controllerFactory) {
            super(controllerFactory);
        }

        public void setAuthorized(boolean authorized) {
            this.authorized = authorized;
        }

        public List<Map<String, Object>> getParameterMaps() {
            return parameterMaps;
        }

        @Override
        public boolean isUserAuthorized(Integer userId, Operation operation, Map<String, Object> parameterMap, String address, boolean audit) throws ControllerException {
            parameterMaps.add(new HashMap<String, Object>(parameterMap));
            if (audit) {
                auditAuthorizationRequest(userId, operation, parameterMap, authorized ? Outcome.SUCCESS : Outcome.FAILURE, address);
            }
            return authorized;
        }

        @Override
        public void addExtensionPermission(ExtensionPermission extensionPermission) {}

        @Override
        public boolean doesUserHaveChannelRestrictions(Integer userId, Operation operation) throws ControllerException {
            return false;
        }

        @Override
        public ChannelAuthorizer getChannelAuthorizer(Integer userId, Operation operation) throws ControllerException {
            return null;
        }

        @Override
        public void usernameChanged(String oldName, String newName) throws ControllerException {}
    }
}
