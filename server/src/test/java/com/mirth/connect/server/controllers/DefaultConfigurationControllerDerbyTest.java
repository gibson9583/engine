/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.mirth.connect.plugins.ExpectedPropertyValue;
import com.mirth.connect.server.mybatis.KeyValuePair;

/** Real embedded-Derby coverage for the checked persistence boundary. */
public class DefaultConfigurationControllerDerbyTest {
    private Path databaseDirectory;
    private String databaseUrl;
    private SqlSessionManager sessions;
    private DefaultConfigurationController controller;

    @Before
    public void setUp() throws Exception {
        databaseDirectory = Files.createTempDirectory("oie-property-safety-");
        databaseUrl = "jdbc:derby:" + databaseDirectory.resolve("db") + ";create=true";
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                Statement statement = connection.createStatement()) {
            // Exact CONFIGURATION definition from the production Derby schema.
            statement.execute("CREATE TABLE CONFIGURATION (CATEGORY VARCHAR(255) NOT NULL, "
                    + "NAME VARCHAR(255) NOT NULL, VALUE CLOB)");
            statement.execute("ALTER TABLE CONFIGURATION ADD PRIMARY KEY (CATEGORY, NAME)");
        }

        UnpooledDataSource dataSource = new UnpooledDataSource(
                "org.apache.derby.jdbc.EmbeddedDriver", databaseUrl, null);
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration(environment);
        configuration.getTypeAliasRegistry().registerAlias("KeyValuePair", KeyValuePair.class);
        try (InputStream mapper = Files.newInputStream(
                Path.of("dbconf/derby/derby-configuration.xml"))) {
            new XMLMapperBuilder(mapper, configuration, "derby-configuration.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        sessions = SqlSessionManager.newInstance(factory);
        controller = new DefaultConfigurationController(() -> sessions, () -> sessions, false);
    }

    @After
    public void tearDown() throws Exception {
        if (databaseUrl != null) {
            try {
                DriverManager.getConnection(databaseUrl + ";shutdown=true");
            } catch (Exception expected) {
                // Derby signals successful database shutdown with an exception.
            }
        }
        if (databaseDirectory != null) {
            try (java.util.stream.Stream<Path> paths = Files.walk(databaseDirectory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    public void explicitAbsenceEmptyValueImmutableGroupAndConflictArePreserved() throws Exception {
        CheckedPropertyValue absent = controller.readPropertyChecked("otel", "policy.v1");
        assertFalse(absent.isPresent());

        assertEquals(AtomicPropertyWriteOutcome.COMMITTED,
                controller.compareAndSetPropertyAtomically("otel", "policy.v1",
                        ExpectedPropertyValue.absent(), "one"));
        CheckedPropertyValue present = controller.readPropertyChecked("otel", "policy.v1");
        assertTrue(present.isPresent());
        assertEquals("one", present.getValue());

        assertEquals(AtomicPropertyWriteOutcome.COMMITTED,
                controller.compareAndSetPropertyAtomically("otel", "policy.v1",
                        ExpectedPropertyValue.present("one"), ""));
        assertEquals("", controller.readPropertyChecked("otel", "policy.v1").getValue());
        assertEquals(AtomicPropertyWriteOutcome.CONFLICT,
                controller.compareAndSetPropertyAtomically("otel", "policy.v1",
                        ExpectedPropertyValue.present("one"), "wrong"));

        Map<String, String> group = controller.readPropertiesForGroupChecked("otel");
        assertEquals("", group.get("policy.v1"));
        assertThrows(UnsupportedOperationException.class,
                () -> group.put("foreign", "value"));
    }

    @Test
    public void twoConcurrentExpectedWritersHaveOneCommitAndOneConflict() throws Exception {
        assertEquals(AtomicPropertyWriteOutcome.COMMITTED,
                controller.compareAndSetPropertyAtomically("otel", "policy.v1",
                        ExpectedPropertyValue.absent(), "prior"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<AtomicPropertyWriteOutcome>> results = new ArrayList<>();
            for (String value : List.of("first", "second")) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return controller.compareAndSetPropertyAtomically("otel", "policy.v1",
                            ExpectedPropertyValue.present("prior"), value);
                }));
            }
            ready.await();
            go.countDown();
            List<AtomicPropertyWriteOutcome> outcomes =
                    List.of(results.get(0).get(), results.get(1).get());
            assertEquals(1, outcomes.stream()
                    .filter(outcome -> outcome == AtomicPropertyWriteOutcome.COMMITTED).count());
            assertEquals(1, outcomes.stream()
                    .filter(outcome -> outcome == AtomicPropertyWriteOutcome.CONFLICT).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void twoConcurrentAbsentWritersHaveOneInsertAndOneConflict() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<AtomicPropertyWriteOutcome>> results = new ArrayList<>();
            for (String value : List.of("first", "second")) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return controller.compareAndSetPropertyAtomically("otel", "policy.v1",
                            ExpectedPropertyValue.absent(), value);
                }));
            }
            ready.await();
            go.countDown();
            List<AtomicPropertyWriteOutcome> outcomes =
                    List.of(results.get(0).get(), results.get(1).get());
            assertEquals(1, outcomes.stream()
                    .filter(outcome -> outcome == AtomicPropertyWriteOutcome.COMMITTED).count());
            assertEquals(1, outcomes.stream()
                    .filter(outcome -> outcome == AtomicPropertyWriteOutcome.CONFLICT).count());
            assertTrue(controller.readPropertyChecked("otel", "policy.v1").isPresent());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void preExpiredAndPreCancelledReadsAreNeverConvertedToAbsence() {
        CheckedReadControl expired = new CheckedReadControl(
                System.nanoTime() - 1L, neverCancelled());
        CheckedReadException timeout = assertThrows(CheckedReadException.class,
                () -> controller.readPropertyChecked("otel", "policy.v1", expired));
        assertEquals(CheckedReadException.Reason.TIMEOUT, timeout.getReason());

        CheckedReadControl cancelled = new CheckedReadControl(
                Long.MAX_VALUE, alreadyCancelled());
        CheckedReadException cancellation = assertThrows(CheckedReadException.class,
                () -> controller.readPropertiesForGroupChecked("otel", cancelled));
        assertEquals(CheckedReadException.Reason.CANCELLED, cancellation.getReason());
    }

    @Test
    public void nullableSchemaRowsAreRejectedInsteadOfBeingConvertedToEmptyOrAbsent()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                java.sql.PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO CONFIGURATION (CATEGORY, NAME, VALUE) VALUES (?, ?, ?)") ) {
            statement.setString(1, "otel");
            statement.setString(2, "policy.v1");
            statement.setNull(3, java.sql.Types.CLOB);
            statement.executeUpdate();
        }

        assertThrows(com.mirth.connect.client.core.ControllerException.class,
                () -> controller.readPropertyChecked("otel", "policy.v1"));
        assertThrows(com.mirth.connect.client.core.ControllerException.class,
                () -> controller.readPropertiesForGroupChecked("otel"));
        assertThrows(com.mirth.connect.client.core.ControllerException.class,
                () -> controller.compareAndSetPropertyAtomically("otel", "policy.v1",
                        ExpectedPropertyValue.absent(), "replacement"));
        assertThrows(com.mirth.connect.client.core.ControllerException.class,
                () -> controller.compareAndSetPropertyAtomically("otel", "policy.v1",
                        ExpectedPropertyValue.present(""), "replacement"));
    }

    private static CheckedReadControl.Cancellation neverCancelled() {
        return new CheckedReadControl.Cancellation() {
            @Override
            public CheckedReadControl.CancellationRegistration register(Runnable callback) {
                return () -> { };
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    private static CheckedReadControl.Cancellation alreadyCancelled() {
        return new CheckedReadControl.Cancellation() {
            @Override
            public CheckedReadControl.CancellationRegistration register(Runnable callback) {
                callback.run();
                return () -> { };
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
    }
}
