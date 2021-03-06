/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.security;

import com.facebook.presto.metadata.QualifiedObjectName;
import com.facebook.presto.spi.CatalogSchemaName;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.security.AccessDeniedException;
import com.facebook.presto.spi.security.Identity;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.spi.security.Privilege.SELECT;
import static com.facebook.presto.transaction.TransactionBuilder.transaction;
import static com.facebook.presto.transaction.TransactionManager.createTestTransactionManager;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class TestFileBasedSystemAccessControl
{
    private static final Identity alice = new Identity("alice", Optional.empty());
    private static final Identity bob = new Identity("bob", Optional.empty());
    private static final Identity admin = new Identity("admin", Optional.empty());
    private static final Identity nonAsciiUser = new Identity("\u0194\u0194\u0194", Optional.empty());
    private static final Set<String> allCatalogs = ImmutableSet.of("secret", "open-to-all", "all-allowed", "alice-catalog", "allowed-absent", "\u0200\u0200\u0200");
    private static final QualifiedObjectName aliceTable = new QualifiedObjectName("alice-catalog", "schema", "table");
    private static final QualifiedObjectName aliceView = new QualifiedObjectName("alice-catalog", "schema", "view");
    private static final CatalogSchemaName aliceSchema = new CatalogSchemaName("alice-catalog", "schema");
    private TransactionManager transactionManager;

    @Test
    public void testCatalogOperations()
    {
       AccessControlManager accessControlManager = newAccessControlManager();

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    assertEquals(accessControlManager.filterCatalogs(admin, allCatalogs), allCatalogs);
                    Set<String> aliceCatalogs = ImmutableSet.of("open-to-all", "alice-catalog", "all-allowed");
                    assertEquals(accessControlManager.filterCatalogs(alice, allCatalogs), aliceCatalogs);
                    Set<String> bobCatalogs = ImmutableSet.of("open-to-all", "all-allowed");
                    assertEquals(accessControlManager.filterCatalogs(bob, allCatalogs), bobCatalogs);
                    Set<String> nonAsciiUserCatalogs = ImmutableSet.of("open-to-all", "all-allowed", "\u0200\u0200\u0200");
                    assertEquals(accessControlManager.filterCatalogs(nonAsciiUser, allCatalogs), nonAsciiUserCatalogs);
                });
    }

    @Test
    public void testSchemaOperations()
    {
        AccessControlManager accessControlManager = newAccessControlManager();

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    Set<String> aliceSchemas = ImmutableSet.of("schema");
                    assertEquals(accessControlManager.filterSchemas(transactionId, alice, "alice-catalog", aliceSchemas), aliceSchemas);
                    assertEquals(accessControlManager.filterSchemas(transactionId, bob, "alice-catalog", aliceSchemas), ImmutableSet.of());

                    accessControlManager.checkCanCreateSchema(transactionId, alice, aliceSchema);
                    accessControlManager.checkCanDropSchema(transactionId, alice, aliceSchema);
                    accessControlManager.checkCanRenameSchema(transactionId, alice, aliceSchema, "new-schema");
                    accessControlManager.checkCanShowSchemas(transactionId, alice, "alice-catalog");
                });
        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateSchema(transactionId, bob, aliceSchema);
        }));
    }

    @Test
    public void testTableOperations()
    {
        AccessControlManager accessControlManager = newAccessControlManager();

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    Set<SchemaTableName> aliceTables = ImmutableSet.of(new SchemaTableName("schema", "table"));
                    assertEquals(accessControlManager.filterTables(transactionId, alice, "alice-catalog", aliceTables), aliceTables);
                    assertEquals(accessControlManager.filterTables(transactionId, bob, "alice-catalog", aliceTables), ImmutableSet.of());

                    accessControlManager.checkCanCreateTable(transactionId, alice, aliceTable);
                    accessControlManager.checkCanDropTable(transactionId, alice, aliceTable);
                    accessControlManager.checkCanSelectFromTable(transactionId, alice, aliceTable);
                    accessControlManager.checkCanInsertIntoTable(transactionId, alice, aliceTable);
                    accessControlManager.checkCanDeleteFromTable(transactionId, alice, aliceTable);
                    accessControlManager.checkCanAddColumns(transactionId, alice, aliceTable);
                    accessControlManager.checkCanRenameColumn(transactionId, alice, aliceTable);
                });
        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateTable(transactionId, bob, aliceTable);
        }));
    }

    @Test
    public void testViewOperations()
            throws Exception
    {
        AccessControlManager accessControlManager = newAccessControlManager();

        transaction(transactionManager, accessControlManager)
                .execute(transactionId -> {
                    accessControlManager.checkCanCreateView(transactionId, alice, aliceView);
                    accessControlManager.checkCanDropView(transactionId, alice, aliceView);
                    accessControlManager.checkCanSelectFromView(transactionId, alice, aliceView);
                    accessControlManager.checkCanCreateViewWithSelectFromTable(transactionId, alice, aliceTable);
                    accessControlManager.checkCanCreateViewWithSelectFromView(transactionId, alice, aliceView);
                    accessControlManager.checkCanSetCatalogSessionProperty(transactionId, alice, "alice-catalog", "property");
                    accessControlManager.checkCanGrantTablePrivilege(transactionId, alice, SELECT, aliceTable, "grantee", true);
                    accessControlManager.checkCanRevokeTablePrivilege(transactionId, alice, SELECT, aliceTable, "revokee", true);
                });
        assertThrows(AccessDeniedException.class, () -> transaction(transactionManager, accessControlManager).execute(transactionId -> {
            accessControlManager.checkCanCreateView(transactionId, bob, aliceView);
        }));
    }

    private AccessControlManager newAccessControlManager()
    {
        transactionManager = createTestTransactionManager();
        AccessControlManager accessControlManager =  new AccessControlManager(transactionManager);

        String path = this.getClass().getClassLoader().getResource("catalog.json").getPath();
        accessControlManager.setSystemAccessControl(FileBasedSystemAccessControl.NAME, ImmutableMap.of("security.config-file", path));

        return accessControlManager;
    }
}
