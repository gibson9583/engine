/* Copyright (c) Mirth Corporation. Published under the MPL. */
package com.mirth.connect.server.controllers;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class PropertySafetySqlMapTest {
    @Test
    public void everySupportedDatabaseDefinesItsRealLockedSelect() throws Exception {
        for (String database : new String[] { "derby", "postgres", "mysql", "sqlserver", "oracle" }) {
            String xml = Files.readString(Path.of("dbconf", database,
                    database + "-configuration.xml"));
            assertTrue(database, xml.contains("selectPropertyForUpdate"));
            assertTrue(database, xml.toUpperCase().contains(database.equals("sqlserver")
                    ? "UPDLOCK" : "FOR UPDATE"));
        }
    }
}
