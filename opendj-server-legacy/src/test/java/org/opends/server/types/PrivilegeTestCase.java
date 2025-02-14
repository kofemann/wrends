/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2007-2010 Sun Microsystems, Inc.
 * Portions Copyright 2011-2016 ForgeRock AS.
 */
package org.opends.server.types;

import static org.forgerock.opendj.ldap.ModificationType.ADD;
import static org.forgerock.opendj.ldap.ModificationType.DELETE;
import static org.forgerock.opendj.ldap.ModificationType.REPLACE;
import static org.forgerock.opendj.ldap.ResultCode.AUTHORIZATION_DENIED;
import static org.forgerock.opendj.ldap.ResultCode.COMPARE_TRUE;
import static org.forgerock.opendj.ldap.ResultCode.INSUFFICIENT_ACCESS_RIGHTS;
import static org.forgerock.opendj.ldap.ResultCode.SUCCESS;
import static org.forgerock.opendj.ldap.ResultCode.UNWILLING_TO_PERFORM;
import static org.forgerock.opendj.ldap.requests.Requests.newModifyDNRequest;
import static org.forgerock.opendj.ldap.requests.Requests.newModifyRequest;
import static org.opends.server.TestCaseUtils.assertNotEquals;
import static org.opends.server.protocols.internal.InternalClientConnection.getRootConnection;
import static org.opends.server.protocols.internal.InternalClientConnection.nextMessageID;
import static org.opends.server.protocols.internal.InternalClientConnection.nextOperationID;
import static org.opends.server.protocols.internal.Requests.newSearchRequest;
import static org.opends.server.types.NullOutputStream.nullPrintStream;
import static org.opends.server.types.Privilege.PASSWORD_RESET;
import static org.opends.server.types.Privilege.SUBENTRY_WRITE;
import static org.opends.server.types.Privilege.UPDATE_SCHEMA;
import static org.opends.server.util.CollectionUtils.newArrayList;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import org.forgerock.opendj.ldap.AttributeDescription;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.ModificationType;
import org.forgerock.opendj.ldap.RDN;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.SearchScope;
import org.forgerock.opendj.ldap.requests.ModifyRequest;
import org.forgerock.opendj.server.config.meta.GlobalCfgDefn.DisabledPrivilege;
import org.forgerock.opendj.server.config.meta.RootDNCfgDefn;
import org.opends.server.TestCaseUtils;
import org.opends.server.api.ClientConnection;
import org.opends.server.backends.task.Task;
import org.opends.server.backends.task.TaskBackend;
import org.opends.server.backends.task.TaskState;
import org.opends.server.controls.ProxiedAuthV1Control;
import org.opends.server.controls.ProxiedAuthV2Control;
import org.opends.server.core.AddOperation;
import org.opends.server.core.AddOperationBasis;
import org.opends.server.core.CompareOperation;
import org.opends.server.core.CompareOperationBasis;
import org.opends.server.core.DeleteOperation;
import org.opends.server.core.DeleteOperationBasis;
import org.opends.server.core.DirectoryServer;
import org.opends.server.core.ModifyDNOperation;
import org.opends.server.core.ModifyDNOperationBasis;
import org.opends.server.core.ModifyOperation;
import org.opends.server.core.ModifyOperationBasis;
import org.opends.server.protocols.internal.InternalClientConnection;
import org.opends.server.protocols.internal.InternalSearchOperation;
import org.opends.server.protocols.internal.Requests;
import org.opends.server.protocols.internal.SearchRequest;
import org.opends.server.tools.RemoteConnection;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.forgerock.opendj.ldap.tools.LDAPModify;
import com.forgerock.opendj.ldap.tools.LDAPPasswordModify;
import com.forgerock.opendj.ldap.tools.LDAPSearch;

/**
 * This class provides a set of test cases for the Directory Server privilege
 * subsystem.
 *
 * FIXME -- It will likely be necessary to also have access control rules in
 *          place to allow operations as necessary once that functionality has
 *          integrated into the server.
 */
public class PrivilegeTestCase extends TypesTestCase
{

  /**
   * The DN of the user that is associated with the internal root connection.
   */
  private static final String INTERNAL_ROOT_DN =
       "cn=Internal Client,cn=Root DNs,cn=config";



  /**
   * A Map of client connections that should be used when performing operations
   * and whether config read operations should be successful.
   */
  private Map<InternalClientConnection, Boolean> connections = new HashMap<>();

  /**
   * Make sure that the server is running and that an appropriate set of
   * structures are in place.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @BeforeClass
  public void setUp()
         throws Exception
  {
    TestCaseUtils.startServer();
    TestCaseUtils.enableBackend("unindexedRoot");

    TestCaseUtils.dsconfig(
            "set-sasl-mechanism-handler-prop",
            "--handler-name", "DIGEST-MD5",
            "--set", "server-fqdn:" + "127.0.0.1");

    TestCaseUtils.initializeTestBackend(true);
    TestCaseUtils.addEntries(
      "dn: cn=Unprivileged Root,cn=Root DNs,cn=config",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "objectClass: ds-cfg-root-dn-user",
      "cn: Unprivileged Root",
      "givenName: Unprivileged",
      "sn: Root",
      "uid: unprivileged.root",
      "userPassword: password",
      "ds-privilege-name: -config-read",
      "ds-privilege-name: -config-write",
      "ds-privilege-name: -password-reset",
      "ds-privilege-name: -update-schema",
      "ds-privilege-name: -ldif-import",
      "ds-privilege-name: -ldif-export",
      "ds-privilege-name: -backend-backup",
      "ds-privilege-name: -backend-restore",
      "ds-privilege-name: -unindexed-search",
      "ds-privilege-name: -subentry-write",
      "",
      "dn: cn=Proxy Root,cn=Root DNs,cn=config",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "objectClass: ds-cfg-root-dn-user",
      "cn: Proxy Root",
      "givenName: Proxy",
      "sn: Root",
      "uid: proxy.root",
      "userPassword: password",
      "ds-privilege-name: proxied-auth",
      "",
      "dn: cn=Privileged User,o=test",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "cn: Privileged User",
      "givenName: Privileged",
      "sn: User",
      "uid: privileged.user",
      "userPassword: password",
      "ds-privilege-name: config-read",
      "ds-privilege-name: config-write",
      "ds-privilege-name: password-reset",
      "ds-privilege-name: update-schema",
      "ds-privilege-name: ldif-import",
      "ds-privilege-name: ldif-export",
      "ds-privilege-name: backend-backup",
      "ds-privilege-name: backend-restore",
      "ds-privilege-name: proxied-auth",
      "ds-privilege-name: bypass-acl",
      "ds-privilege-name: unindexed-search",
      "ds-privilege-name: subentry-write",
      "ds-pwp-password-policy-dn: cn=Clear UserPassword Policy," +
           "cn=Password Policies,cn=config",
      "",
      "dn: cn=Unprivileged User,o=test",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "cn: Unprivileged User",
      "givenName: Unprivileged",
      "sn: User",
      "uid: unprivileged.user",
      "ds-privilege-name: bypass-acl",
      "userPassword: password",
      "ds-pwp-password-policy-dn: cn=Clear UserPassword Policy," +
           "cn=Password Policies,cn=config",
      "",
      "dn: cn=Subentry Target,o=test",
      "objectClass: top",
      "objectClass: subentry",
      "objectClass: collectiveAttributeSubentry",
      "objectClass: extensibleObject",
      "cn: Subentry Target",
      "l;collective: Test",
      "subtreeSpecification: {}",
      "",
      "dn: cn=PWReset Target,o=test",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "cn: PWReset Target",
      "givenName: PWReset",
      "sn: Target",
      "uid: pwreset.target",
      "userPassword: password");

    TestCaseUtils.applyModifications(false,
      "dn: o=test",
      "changetype: modify",
      "add: aci",
      "aci: (version 3.0; acl \"Proxy Root\"; allow (proxy) " +
           "userdn=\"ldap:///cn=Proxy Root,cn=Root DNs,cn=config\";)",
      "aci: (version 3.0; acl \"Unprivileged Root\"; allow (proxy) " +
           "userdn=\"ldap:///cn=Unprivileged Root,cn=Root DNs,cn=config\";)",
      "aci: (version 3.0; acl \"Privileged User\"; allow (proxy) " +
           "userdn=\"ldap:///cn=Privileged User,o=test\";)",
      "aci: (targetattr=\"*\")(version 3.0; acl \"PWReset Target\"; " +
           "allow (all) userdn=\"ldap:///cn=PWReset Target,o=test\";)");


    // Build the array of connections we will use to perform the tests.
    connections.put(new InternalClientConnection(new AuthenticationInfo()), false);
    connections.put(InternalClientConnection.getRootConnection(), true);
    connections.put(newConn("cn=Directory Manager,cn=Root DNs,cn=config", true), true);
    connections.put(newConn("cn=Unprivileged Root,cn=Root DNs,cn=config", true), false);
    connections.put(newConn("cn=Proxy Root,cn=Root DNs,cn=config", true), true);
    connections.put(newConn("cn=Unprivileged User,o=test", false), false);
    connections.put(newConn("cn=Privileged User,o=test", false), true);


    TestCaseUtils.addEntries(
        "dn: dc=unindexed,dc=jeb",
        "objectClass: top",
        "objectClass: domain",
        "",
        "dn: cn=test1 user,dc=unindexed,dc=jeb",
        "objectClass: top",
        "objectClass: person",
        "objectClass: organizationalPerson",
        "objectClass: inetOrgPerson",
        "cn: test1 user",
        "givenName: user",
        "sn: test1",
        "carLicense: test1",
        "",
        "dn: cn=test2 user,dc=unindexed,dc=jeb",
        "objectClass: top",
        "objectClass: person",
        "objectClass: organizationalPerson",
        "objectClass: inetOrgPerson",
        "cn: test2 user",
        "givenName: user",
        "sn: test2",
        "carLicense: test2"
    );
    for (int i = 0; i < 5000; i++)
    {
      String userNb = "user." + i;
      TestCaseUtils.addEntry(
          "dn: cn=" + userNb + ",dc=unindexed,dc=jeb",
          "objectClass: top",
          "objectClass: person",
          "objectClass: organizationalPerson",
          "objectClass: inetOrgPerson",
          "cn: " + userNb,
          "givenName: " + userNb,
          "sn: " + userNb,
          "carLicense: " + userNb
      );
    }
  }

  private InternalClientConnection newConn(String userDN,
      boolean isRoot)
      throws DirectoryException
  {
    Entry userEntry = DirectoryServer.getEntry(DN.valueOf(userDN));
    AuthenticationInfo authInfo = new AuthenticationInfo(userEntry, isRoot);
    return new InternalClientConnection(authInfo);
  }



  /**
   * Cleans up anything that might be left around after running the tests in
   * this class.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @AfterClass
  public void cleanUp() throws Exception
  {
    TestCaseUtils.dsconfig(
            "set-sasl-mechanism-handler-prop",
            "--handler-name", "DIGEST-MD5",
            "--remove", "server-fqdn:" + "127.0.0.1");

    assertDeleteSuccessfully("cn=Unprivileged Root,cn=Root DNs,cn=config");
    assertDeleteSuccessfully("cn=Proxy Root,cn=Root DNs,cn=config");
    assertDeleteSuccessfully("cn=Privileged User,o=test");
    assertDeleteSuccessfully("cn=UnPrivileged User,o=test");
    assertDeleteSuccessfully("cn=PWReset Target,o=test");
    assertDeleteSuccessfully("cn=test1 user,dc=unindexed,dc=jeb");
    assertDeleteSuccessfully("cn=test2 user,dc=unindexed,dc=jeb");
    for (int i = 0; i < 5000; i++)
    {
      assertDeleteSuccessfully("cn=user." + i + ",dc=unindexed,dc=jeb");
    }
    assertDeleteSuccessfully("dc=unindexed,dc=jeb");

    TestCaseUtils.disableBackend("unindexedRoot");
  }

  private void assertDeleteSuccessfully(String dn) throws DirectoryException
  {
    DeleteOperation deleteOperation = getRootConnection().processDelete(dn);
    assertEquals(deleteOperation.getResultCode(), ResultCode.SUCCESS);
  }



  /**
   * Retrieves a set of data that can be used for performing the tests.  The
   * arguments generated for each method will be:
   * <OL>
   *   <LI>A client connection to use to perform the operation</LI>
   *   <LI>A flag indicating whether the operation should succeed</LI>
   * </OL>
   *
   * @return  A set of data that can be used for performing the tests.
   */
  @DataProvider(name = "testdata")
  public Object[][] getTestData()
  {
    Object[][] returnArray = new Object[connections.size()][2];
    int i = 0;
    for (Map.Entry<InternalClientConnection, Boolean> entry : connections
        .entrySet())
    {
      returnArray[i][0] = entry.getKey();
      returnArray[i][1] = entry.getValue();
      i++;
    }

    return returnArray;
  }



  /**
   * Tests to ensure that search operations in the server configuration properly
   * respect the CONFIG_READ privilege.
   *
   * @param  conn          The client connection to use to perform the search
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the CONFIG_READ privilege and therefore the
   *                       search should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testConfigReadSearch(InternalClientConnection conn,
                                   boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.CONFIG_READ, null), hasPrivilege);

    SearchRequest request = Requests.newSearchRequest(DN.valueOf("cn=config"), SearchScope.BASE_OBJECT);
    InternalSearchOperation searchOperation = conn.processSearch(request);
    assertPrivilege(searchOperation.getResultCode(), hasPrivilege);
  }

  /**
   * Tests to ensure that unindexed search operations properly respect the
   * UNINDEXED_SEARCH privilege.
   *
   * @param conn The client connection to use to perform the search operation.
   *
   * @param hasPrivilege Indicates whether the authenticated user is expected
   *                     to have the UNINDEXED_SEARCH privilege and therefore
   *                     the search should succeed.
   * @throws Exception If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testUnindexedSearch(InternalClientConnection conn,
                                  boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.UNINDEXED_SEARCH, null), hasPrivilege);

    SearchRequest request = newSearchRequest("dc=unindexed,dc=jeb", SearchScope.WHOLE_SUBTREE, "(carLicense=test*)");
    InternalSearchOperation searchOperation = conn.processSearch(request);
    assertPrivilege(searchOperation.getResultCode(), hasPrivilege);
  }

  private void assertPrivilege(ResultCode actual, boolean hasPrivilege)
  {
    assertEquals(actual, hasPrivilegeRC(hasPrivilege));
  }

  private ResultCode hasPrivilegeRC(boolean hasPrivilege)
  {
    return hasPrivilege ? SUCCESS : INSUFFICIENT_ACCESS_RIGHTS;
  }

  private void assertProxyPrivilege(ResultCode actual, boolean hasProxyPrivilege)
  {
    assertEquals(actual, hasProxyPrivilege ? SUCCESS : AUTHORIZATION_DENIED);
  }

  /**
   * Tests to ensure that compare operations in the server configuration
   * properly respect the CONFIG_READ privilege.
   *
   * @param  conn          The client connection to use to perform the compare
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the CONFIG_READ privilege and therefore the
   *                       compare should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testConfigReadCompare(InternalClientConnection conn,
                                    boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.CONFIG_READ, null), hasPrivilege);

    CompareOperation compareOperation = conn.processCompare("cn=config", "cn", "config");
    if (hasPrivilege)
    {
      assertEquals(compareOperation.getResultCode(), COMPARE_TRUE);
    }
    else
    {
      assertEquals(compareOperation.getResultCode(), INSUFFICIENT_ACCESS_RIGHTS);
    }
  }



  /**
   * Tests to ensure that add and delete operations in the server configuration
   * properly respect the CONFIG_WRITE privilege.
   *
   * @param  conn          The client connection to use to perform the
   *                       operations.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the CONFIG_WRITE privilege and therefore the
   *                       operations should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testConfigWriteAddAndDelete(InternalClientConnection conn,
                                          boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.CONFIG_WRITE, null), hasPrivilege);

    Entry entry = TestCaseUtils.makeEntry(
      "dn: cn=Test Root,cn=Root DNs,cn=config",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "objectClass: ds-cfg-root-dn-user",
      "cn: Test Root",
      "givenName: Test",
      "sn: Root",
      "userPassword: password");

    AddOperation addOperation = conn.processAdd(entry);
    assertPrivilege(addOperation.getResultCode(), hasPrivilege);

    DN dnToRemove = entry.getName();
    if (!hasPrivilege)
    {
      dnToRemove = DN.valueOf("cn=File-Based Access Logger,cn=Loggers,cn=config");
    }
    DeleteOperation deleteOperation = conn.processDelete(dnToRemove);
    assertPrivilege(deleteOperation.getResultCode(), hasPrivilege);
  }



  /**
   * Tests to ensure that modify operations in the server configuration
   * properly respect the CONFIG_WRITE privilege.
   *
   * @param  conn          The client connection to use to perform the modify
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the CONFIG_WRITE privilege and therefore the
   *                       modify should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testConfigWriteModify(InternalClientConnection conn,
                                    boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.CONFIG_WRITE, null), hasPrivilege);

    ModifyRequest modifyRequest = newModifyRequest("cn=config")
        .addModification(REPLACE, "ds-cfg-size-limit", "2000");
    ModifyOperation modifyOperation = conn.processModify(modifyRequest);
    assertPrivilege(modifyOperation.getResultCode(), hasPrivilege);

    if (hasPrivilege)
    {
      modifyRequest = newModifyRequest("cn=config")
          .addModification(REPLACE, "ds-cfg-size-limit", "1000");
      modifyOperation = conn.processModify(modifyRequest);
      assertEquals(modifyOperation.getResultCode(), ResultCode.SUCCESS);
    }
  }



  /**
   * Tests to ensure that modify DN operations in the server configuration
   * properly respect the CONFIG_WRITE privilege.
   *
   * @param  conn          The client connection to use to perform the modify DN
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the CONFIG_WRITE privilege and therefore the
   *                       modify DN should succeed (or at least get past the
   *                       privilege check, only to fail because we don't
   *                       support modify DN in the server configuration).
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testConfigWriteModifyDN(InternalClientConnection conn,
                                      boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.CONFIG_WRITE, null), hasPrivilege);

    processModifyDN(
        conn, "cn=Work Queue,cn=config", "cn=New RDN for Work Queue",
        // We don't support modify DN operations in the server configuration, but
        // at least we need to make sure we're getting past the privilege check.
        (hasPrivilege ? UNWILLING_TO_PERFORM : INSUFFICIENT_ACCESS_RIGHTS));
  }



  /**
   * Tests to ensure that add and delete operations
   * properly respect the SUBENTRY_WRITE privilege.
   *
   * @param  conn          The client connection to use to perform the
   *                       operations.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the SUBENTRY_WRITE privilege and therefore
   *                       the operations should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testSubentryWriteAddAndDelete(InternalClientConnection conn,
                                          boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.SUBENTRY_WRITE, null),
            hasPrivilege);

    Entry entry = TestCaseUtils.makeEntry(
      "dn: cn=Test Subentry,o=test",
      "objectClass: top",
      "objectClass: subentry",
      "objectClass: collectiveAttributeSubentry",
      "objectClass: extensibleObject",
      "cn: Test Subentry",
      "l;collective: Test",
      "subtreeSpecification: {}");

    AddOperation addOperation = conn.processAdd(entry);
    assertPrivilege(addOperation.getResultCode(), hasPrivilege);

    DN dnToRemove = entry.getName();
    if (!hasPrivilege)
    {
      dnToRemove = DN.valueOf("cn=Subentry Target,o=test");
    }
    DeleteOperation deleteOperation = conn.processDelete(dnToRemove);
    assertPrivilege(deleteOperation.getResultCode(), hasPrivilege);
  }



  /**
   * Tests to ensure that modify operations properly respect
   * the SUBENTRY_WRITE privilege.
   *
   * @param  conn          The client connection to use to perform the modify
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the SUBENTRY_WRITE privilege and therefore
   *                       the modify should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testSubentryWriteModify(InternalClientConnection conn,
                                    boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.SUBENTRY_WRITE, null),
            hasPrivilege);

    ModifyRequest modifyRequest = newModifyRequest("cn=Subentry Target,o=test")
        .addModification(REPLACE, "subtreeSpecification", "{base \"ou=doesnotexist\"}");
    ModifyOperation modifyOperation = conn.processModify(modifyRequest);
    assertPrivilege(modifyOperation.getResultCode(), hasPrivilege);

    if (hasPrivilege)
    {
      modifyRequest = newModifyRequest("cn=Subentry Target,o=test")
          .addModification(REPLACE, "subtreeSpecification", "{}");
      modifyOperation = conn.processModify(modifyRequest);
      assertEquals(modifyOperation.getResultCode(), ResultCode.SUCCESS);
    }
  }



  /**
   * Tests to ensure that modify DN operations
   * properly respect the SUBENTRY_WRITE privilege.
   *
   * @param  conn          The client connection to use to perform the modify DN
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the SUBENTRY_WRITE privilege and therefore
   *                       the modify DN should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testSubentryWriteModifyDN(InternalClientConnection conn,
                                      boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(SUBENTRY_WRITE, null), hasPrivilege);

    processModifyDN(conn, "cn=Subentry Target,o=test", "cn=New Subentry Target", hasPrivilegeRC(hasPrivilege));
    if (hasPrivilege)
    {
      processModifyDN(conn, "cn=New Subentry Target,o=test", "cn=Subentry Target", SUCCESS);
    }
  }

  /**
   * Tests to ensure that modify operations which attempt to reset a user's
   * password properly respect the PASSWORD_RESET privilege.
   *
   * @param  conn          The client connection to use to perform the password
   *                       reset.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the PASSWORD_RESET privilege and therefore
   *                       the password reset should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testPasswordResetModify(InternalClientConnection conn,
                                      boolean hasPrivilege)
         throws Exception
  {
    // We've got to do this as an external operation rather than internal, so
    // get the bind DN and password to use from the client connection.

    String userDN;
    String userPassword;
    Entry  authNEntry = conn.getAuthenticationInfo().getAuthenticationEntry();
    if (authNEntry == null)
    {
      userDN       = "";
      userPassword = "";
    }
    else if (authNEntry.getName().toString().equalsIgnoreCase(INTERNAL_ROOT_DN))
    {
      return;
    }
    else
    {
      userDN       = authNEntry.getName().toString();
      userPassword = "password";
    }

    assertEquals(conn.hasPrivilege(PASSWORD_RESET, null), hasPrivilege);

    String path = TestCaseUtils.createTempFile(
      "dn: cn=PWReset Target,o=test",
      "changetype: modify",
      "replace: userPassword",
      "userPassword: newpassword",
      "",
      "dn: cn=PWReset Target,o=test",
      "changetype: modify",
      "replace: userPassword",
      "userPassword: password");

    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-D", userDN,
      "-w", userPassword,
      "-f", path
    };

    int resultCode = LDAPModify.run(nullPrintStream(), nullPrintStream(), args);
    if (hasPrivilege)
    {
      assertEquals(resultCode, 0);
    }
    else
    {
      assertEquals(resultCode, 50);
    }
  }



  /**
   * Tests to ensure that password modify extended operations which attempt to
   * reset a user's password properly respect the PASSWORD_RESET privilege.
   *
   * @param  conn          The client connection to use to perform the password
   *                       reset.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the PASSWORD_RESET privilege and therefore
   *                       the password reset should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testPasswordResetExtOp(InternalClientConnection conn,
                                     boolean hasPrivilege)
         throws Exception
  {
    // We've got to do this as an external operation rather than internal, so
    // get the bind DN and password to use from the client connection.

    String userDN;
    String userPassword;
    Entry  authNEntry = conn.getAuthenticationInfo().getAuthenticationEntry();
    if (authNEntry == null)
    {
      userDN       = "";
      userPassword = "";
    }
    else if (authNEntry.getName().toString().equalsIgnoreCase(INTERNAL_ROOT_DN))
    {
      return;
    }
    else
    {
      userDN       = authNEntry.getName().toString();
      userPassword = "password";
    }

    assertEquals(conn.hasPrivilege(Privilege.PASSWORD_RESET, null),
                 hasPrivilege);

    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-D", userDN,
      "-w", userPassword,
      "-a", "dn:cn=PWReset Target,o=test",
      "-n", "newpassword"
    };

    int resultCode =
             LDAPPasswordModify.run(nullPrintStream(), nullPrintStream(), args);
    if (hasPrivilege)
    {
      assertEquals(resultCode, 0);

      args = new String[]
      {
        "--noPropertiesFile",
        "-h", "127.0.0.1",
        "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
        "-D", userDN,
        "-w", userPassword,
        "-a", "dn:cn=PWReset Target,o=test",
        "-n", "password"
      };
      assertEquals(LDAPPasswordModify.run(nullPrintStream(), nullPrintStream(), args), 0);
    }
    else
    {
      assertEquals(resultCode, 50);
    }
  }



  /**
   * Tests to ensure that attempts to update the schema with a modify operation
   * will properly respect the UPDATE_SCHEMA privilege.
   *
   * @param  conn          The client connection to use to perform the schema
   *                       update.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the UPDATE_SCHEMA privilege and therefore
   *                       the schema update should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testUpdateSchemaModify(InternalClientConnection conn,
                               boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(UPDATE_SCHEMA, null), hasPrivilege);

    String attrDefinition =
         "( testupdateschemaat-oid NAME 'testUpdateSchemaAT' " +
         "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE " +
         "X-ORIGIN 'PrivilegeTestCase' )";

    ModifyRequest modifyRequest = newModifyRequest("cn=schema")
        .addModification(ADD, "attributetypes", attrDefinition);
    ModifyOperation modifyOperation = conn.processModify(modifyRequest);
    assertPrivilege(modifyOperation.getResultCode(), hasPrivilege);

    if (hasPrivilege)
    {
      modifyRequest = newModifyRequest("cn=schema")
          .addModification(DELETE, "attributetypes", attrDefinition);
      modifyOperation = conn.processModify(modifyRequest);
      assertEquals(modifyOperation.getResultCode(), ResultCode.SUCCESS);
    }
  }



  /**
   * Tests to ensure that attempts to update the schema with an add schema file
   * task will properly respect the UPDATE_SCHEMA privilege.
   *
   * @param  conn          The client connection to use to perform the schema
   *                       update.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the UPDATE_SCHEMA privilege and therefore
   *                       the schema update should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testUpdateSchemaAddSchemaFile(InternalClientConnection conn,
                                            boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(UPDATE_SCHEMA, null), hasPrivilege);

    String identifier;
    Entry authNEntry = conn.getAuthenticationInfo().getAuthenticationEntry();
    if (authNEntry == null)
    {
      identifier = "null";
    }
    else
    {
      identifier = authNEntry.getName().toString();
      identifier = identifier.replace(',', '-');
      identifier = identifier.replace(' ', '-');
      identifier = identifier.replace('=', '-');
    }

    String[] fileLines =
    {
      "dn: cn=schema",
      "objectClass: top",
      "objectClass: ldapSubentry",
      "objectClass: subschema",
      "attributeTypes: ( " + identifier.toLowerCase() + "-oid"
          + " NAME '" + identifier + "'"
          + " SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )"
    };

    String schemaDirectory = DirectoryServer.getEnvironmentConfig().getSchemaDirectory().getPath();
    File validFile = new File(schemaDirectory, "05-" + identifier + ".ldif");
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(validFile)))
    {
      for (String line : fileLines)
      {
        writer.write(line);
        writer.newLine();
      }
    }

    assertPrivilege(conn, hasPrivilege,
      "dn: ds-task-id=" + UUID.randomUUID() + ",cn=Scheduled Tasks,cn=Tasks",
      "objectClass: top",
      "objectClass: ds-task",
      "objectClass: ds-task-add-schema-file",
      "ds-task-class-name: org.opends.server.tasks.AddSchemaFileTask",
      "ds-task-schema-file-name: 05-" + identifier + ".ldif");
  }

  private void assertPrivilege(InternalClientConnection conn, boolean hasPrivilege, String... lines) throws Exception
  {
    Entry taskEntry = TestCaseUtils.makeEntry(lines);

    AddOperation addOperation = conn.processAdd(taskEntry);
    assertPrivilege(addOperation.getResultCode(), hasPrivilege);

    if (hasPrivilege)
    {
      Task task = getCompletedTask(taskEntry.getName());
      assertNotNull(task);
      assertTrue(TaskState.isSuccessful(task.getTaskState()));
    }
  }



  /**
   * Tests to ensure that attempts to backup the Directory Server backends
   * will properly respect the BACKEND_BACKUP privilege.
   *
   * @param  conn          The client connection to use to perform the backup.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the BACKEND_BACKUP privilege and therefore
   *                       the backup should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata", groups = { "slow" })
  public void testBackupBackend(InternalClientConnection conn,
                                boolean hasPrivilege)
         throws Exception
  {
    // We have to sleep here because the backup ID that gets generated will be
    // based on a timestamp and we don't want two in the same second.
    Thread.sleep(1100);

    assertEquals(conn.hasPrivilege(Privilege.BACKEND_BACKUP, null),
                 hasPrivilege);

    assertPrivilege(conn, hasPrivilege,
      "dn: ds-task-id=" + UUID.randomUUID() + ",cn=Scheduled Tasks,cn=Tasks",
      "objectclass: top",
      "objectclass: ds-task",
      "objectclass: ds-task-backup",
      "ds-task-class-name: org.opends.server.tasks.BackupTask",
      "ds-backup-directory-path: bak",
      "ds-task-backup-all: TRUE");
  }



  /**
   * Tests to ensure that attempts to restore the Directory Server backends
   * will properly respect the BACKEND_RESTORE privilege.
   *
   * @param  conn          The client connection to use to perform the restore.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the BACKEND_RESTORE privilege and therefore
   *                       the restore should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata", groups = { "slow" },
        dependsOnMethods = { "testBackupBackend" })
  public void testRestoreBackend(InternalClientConnection conn,
                                 boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.BACKEND_RESTORE, null),
                 hasPrivilege);

    assertPrivilege(conn, hasPrivilege,
      "dn: ds-task-id=" + UUID.randomUUID() + ",cn=Scheduled Tasks,cn=Tasks",
      "objectclass: top",
      "objectclass: ds-task",
      "objectclass: ds-task-restore",
      "ds-task-class-name: org.opends.server.tasks.RestoreTask",
      "ds-backup-directory-path: bak" + File.separator + "userRoot");
  }



  /**
   * Tests to ensure that attempts to export the contents of a Directory Server
   * backend will properly respect the LDIF_EXPORT privilege.
   *
   * @param  conn          The client connection to use to perform the export.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the LDIF_EXPORT privilege and therefore
   *                       the export should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata", groups = { "slow" })
  public void testLDIFExport(InternalClientConnection conn,
                             boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.LDIF_EXPORT, null), hasPrivilege);

    File   tempFile     = File.createTempFile("export-", ".ldif");
    String tempFilePath = tempFile.getAbsolutePath();
    tempFile.delete();

    try
    {
      assertPrivilege(conn, hasPrivilege,
          "dn: ds-task-id=" + UUID.randomUUID() + ",cn=Scheduled Tasks,cn=Tasks",
          "objectclass: top",
          "objectclass: ds-task",
          "objectclass: ds-task-export",
          "ds-task-class-name: org.opends.server.tasks.ExportTask",
          "ds-task-export-backend-id: userRoot",
          "ds-task-export-ldif-file: " + tempFilePath);
    }
    finally
    {
      tempFile.delete();
    }
  }



  /**
   * Tests to ensure that attempts to import into a Directory Server backend
   * will properly respect the LDIF_IMPORT privilege.
   *
   * @param  conn          The client connection to use to perform the import.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the LDIF_IMPORT privilege and therefore
   *                       the import should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata", groups = { "slow" })
  public void testLDIFImport(InternalClientConnection conn,
                             boolean hasPrivilege)
         throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.LDIF_IMPORT, null), hasPrivilege);

    String path = TestCaseUtils.createTempFile(
      "dn: dc=example,dc=com",
      "objectClass: top",
      "objectClass: domain",
      "dc: example");

    assertPrivilege(conn, hasPrivilege,
      "dn: ds-task-id=" + UUID.randomUUID() + ",cn=Scheduled Tasks,cn=Tasks",
      "objectclass: top",
      "objectclass: ds-task",
      "objectclass: ds-task-import",
      "ds-task-class-name: org.opends.server.tasks.ImportTask",
      "ds-task-import-backend-id: userRoot",
      "ds-task-import-ldif-file: " + path);
  }

  /**
   * Test to ensure that attempts to rebuild indexes will property respect
   * the LDIF_IMPORT privilege.
   *
   * @param conn The client connection to use to perform the rebuild.
   * @param hasPrivilege Indicates weather the authenticated user is
   *                     expected to have the INDEX_REBUILD privilege
   *                     and therefore the rebuild should succeed.
   * @throws Exception if an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata", groups = { "slow" })
  public void testRebuildIndex(InternalClientConnection conn,
                               boolean hasPrivilege)
      throws Exception
  {
    assertEquals(conn.hasPrivilege(Privilege.LDIF_IMPORT, null), hasPrivilege);

    assertPrivilege(conn, hasPrivilege,
      "dn: ds-task-id=" + UUID.randomUUID() + ",cn=Scheduled Tasks,cn=Tasks",
      "objectclass: top",
      "objectclass: ds-task",
      "objectclass: ds-task-rebuild",
      "ds-task-class-name: org.opends.server.tasks.RebuildTask",
      "ds-task-rebuild-base-dn: dc=example,dc=com",
      "ds-task-rebuild-index: cn");
  }



  /**
   * Tests to ensure that the use of the Directory Server will properly respect
   * the PROXIED_AUTH privilege for add, delete, modify and modify DN requests
   * that contain the proxied auth v1 control.
   *
   * @param  conn          The client connection to use to perform the
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the PROXIED_AUTH privilege and therefore
   *                       the operation should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testProxyAuthV1Write(InternalClientConnection conn,
                                   boolean hasPrivilege)
         throws Exception
  {
    // We can't trust the value of hasPrivilege because root users don't get
    // proxy privileges by default.  So make the determination based on the
    // privileges the user actually has.
    boolean hasProxyPrivilege = conn.hasPrivilege(Privilege.PROXIED_AUTH, null);

    Entry e = TestCaseUtils.makeEntry(
      "dn: cn=ProxyV1 Test,o=test",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "cn: ProxyV1 Test",
      "givenName: ProxyV1",
      "sn: Test");

    List<Control> controls = new ArrayList<>(1);
    controls.add(new ProxiedAuthV1Control(DN.valueOf("cn=PWReset Target,o=test")));


    // Try to add the entry.  If this fails with the proxy control, then add it
    // with a root connection so we can do other things with it.
    AddOperation addOperation = new AddOperationBasis(conn, nextOperationID(), nextMessageID(), controls, e);
    addOperation.run();
    assertProxyPrivilege(addOperation.getResultCode(), hasProxyPrivilege);

    if (!hasProxyPrivilege)
    {
      TestCaseUtils.addEntry(e);
    }


    // Try to modify the entry to add a description.
    List<Modification> mods = newModifications(REPLACE, "description", "foo");
    ModifyOperation modifyOperation = runModifyOperation(conn, e, controls, mods);
    assertProxyPrivilege(modifyOperation.getResultCode(), hasProxyPrivilege);


    // Try to rename the entry.
    ModifyDNOperation modifyDNOperation = new ModifyDNOperationBasis(conn, nextOperationID(),
                               nextMessageID(), controls, e.getName(),
                               RDN.valueOf("cn=Proxy V1 Test"), true, null);
    modifyDNOperation.run();
    assertProxyPrivilege(modifyOperation.getResultCode(), hasProxyPrivilege);

    DN newEntryDN = e.getName();
    if (hasProxyPrivilege)
    {
      newEntryDN = modifyDNOperation.getNewDN();
    }


    // Try to delete the operation.  If this fails, then delete it with a root
    // connection so it gets cleaned up.
    DeleteOperation deleteOperation = new DeleteOperationBasis(conn, nextOperationID(), nextMessageID(),
                             controls, newEntryDN);
    deleteOperation.run();
    assertProxyPrivilege(deleteOperation.getResultCode(), hasProxyPrivilege);

    if (!hasProxyPrivilege)
    {
      DeleteOperation delOp = getRootConnection().processDelete(newEntryDN);
      assertEquals(delOp.getResultCode(), ResultCode.SUCCESS);
    }
  }

  private List<Modification> newModifications(ModificationType modType, String attrName, String attrValue)
  {
    return newArrayList(new Modification(modType, Attributes.create(attrName, attrValue)));
  }

  /**
   * Tests to ensure that the use of the Directory Server will properly respect
   * the PROXIED_AUTH privilege for search and compare requests that contain the
   * proxied auth v1 control.
   *
   * @param  conn          The client connection to use to perform the
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the PROXIED_AUTH privilege and therefore
   *                       the operation should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testProxyAuthV1Read(InternalClientConnection conn,
                                  boolean hasPrivilege)
         throws Exception
  {
    // We can't trust the value of hasPrivilege because root users don't get
    // proxy privileges by default.  So make the determination based on the
    // privileges the user actually has.
    boolean hasProxyPrivilege = conn.hasPrivilege(Privilege.PROXIED_AUTH, null);

    DN targetDN = DN.valueOf("cn=PWReset Target,o=test");
    List<Control> controls = new ArrayList<>(1);
    controls.add(new ProxiedAuthV1Control(targetDN));


    // Test a compare operation against the PWReset Target user.
    CompareOperation compareOperation = new CompareOperationBasis(conn,
                              nextOperationID(), nextMessageID(),
                              controls, targetDN, AttributeDescription.valueOf("cn"),
                              ByteString.valueOfUtf8("PWReset Target"));
    compareOperation.run();
    if (hasProxyPrivilege)
    {
      assertEquals(compareOperation.getResultCode(), COMPARE_TRUE);
    }
    else
    {
      assertEquals(compareOperation.getResultCode(), AUTHORIZATION_DENIED);
    }


    // Test a search operation against the PWReset Target user.
    SearchRequest request = newSearchRequest(targetDN, SearchScope.BASE_OBJECT).addControl(controls);
    InternalSearchOperation searchOperation = conn.processSearch(request);
    assertProxyPrivilege(searchOperation.getResultCode(), hasProxyPrivilege);
  }



  /**
   * Tests to ensure that the use of the Directory Server will properly respect
   * the PROXIED_AUTH privilege for add, delete, modify and modify DN requests
   * that contain the proxied auth v2 control.
   *
   * @param  conn          The client connection to use to perform the
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the PROXIED_AUTH privilege and therefore
   *                       the operation should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testProxyAuthV2Write(InternalClientConnection conn,
                                   boolean hasPrivilege)
         throws Exception
  {
    // We can't trust the value of hasPrivilege because root users don't get
    // proxy privileges by default.  So make the determination based on the
    // privileges the user actually has.
    boolean hasProxyPrivilege = conn.hasPrivilege(Privilege.PROXIED_AUTH, null);

    Entry e = TestCaseUtils.makeEntry(
      "dn: cn=ProxyV2 Test,o=test",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "cn: ProxyV2 Test",
      "givenName: ProxyV2",
      "sn: Test");

    List<Control> controls = new ArrayList<>(1);
    controls.add(new ProxiedAuthV2Control(ByteString.valueOfUtf8("dn:cn=PWReset Target,o=test")));


    // Try to add the entry.  If this fails with the proxy control, then add it
    // with a root connection so we can do other things with it.
    AddOperation addOperation = new AddOperationBasis(conn, nextOperationID(), nextMessageID(), controls, e);
    addOperation.run();
    assertProxyPrivilege(addOperation.getResultCode(), hasProxyPrivilege);

    if (!hasProxyPrivilege)
    {
      TestCaseUtils.addEntry(e);
    }


    List<Modification> mods = newModifications(REPLACE, "description", "foo");
    ModifyOperation modifyOperation = runModifyOperation(conn, e, controls, mods);
    assertProxyPrivilege(modifyOperation.getResultCode(), hasProxyPrivilege);


    // Try to rename the entry.
    ModifyDNOperation modifyDNOperation = new ModifyDNOperationBasis(conn, nextOperationID(),
                               nextMessageID(), controls, e.getName(),
                               RDN.valueOf("cn=Proxy V2 Test"), true, null);
    modifyDNOperation.run();
    assertProxyPrivilege(modifyDNOperation.getResultCode(), hasProxyPrivilege);

    DN newEntryDN = e.getName();
    if (hasProxyPrivilege)
    {
      newEntryDN = modifyDNOperation.getNewDN();
    }


    // Try to delete the operation.  If this fails, then delete it with a root
    // connection so it gets cleaned up.
    DeleteOperationBasis deleteOperation = new DeleteOperationBasis(conn, nextOperationID(), nextMessageID(),
                             controls, newEntryDN);
    deleteOperation.run();
    assertProxyPrivilege(deleteOperation.getResultCode(), hasProxyPrivilege);

    if (!hasProxyPrivilege)
    {
      DeleteOperation delOp = getRootConnection().processDelete(newEntryDN);
      assertEquals(delOp.getResultCode(), ResultCode.SUCCESS);
    }
  }

  private ModifyOperation runModifyOperation(InternalClientConnection conn, Entry e, List<Control> controls,
      List<Modification> mods)
  {
    ModifyOperation op =
        new ModifyOperationBasis(conn, nextOperationID(), nextMessageID(), controls, e.getName(), mods);
    op.run();
    return op;
  }



  /**
   * Tests to ensure that the use of the Directory Server will properly respect
   * the PROXIED_AUTH privilege for search and compare requests that contain the
   * proxied auth v2 control.
   *
   * @param  conn          The client connection to use to perform the
   *                       operation.
   * @param  hasPrivilege  Indicates whether the authenticated user is expected
   *                       to have the PROXIED_AUTH privilege and therefore
   *                       the operation should succeed.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test(dataProvider = "testdata")
  public void testProxyAuthV2Read(InternalClientConnection conn,
                                  boolean hasPrivilege)
         throws Exception
  {
    // We can't trust the value of hasPrivilege because root users don't get
    // proxy privileges by default.  So make the determination based on the
    // privileges the user actually has.
    boolean hasProxyPrivilege = conn.hasPrivilege(Privilege.PROXIED_AUTH, null);

    DN targetDN = DN.valueOf("cn=PWReset Target,o=test");
    List<Control> controls = new ArrayList<>(1);
    controls.add(new ProxiedAuthV2Control(ByteString.valueOfUtf8("dn:" + targetDN)));


    // Test a compare operation against the PWReset Target user.
    CompareOperation compareOperation = new CompareOperationBasis(conn, nextOperationID(),
                              nextMessageID(), controls, targetDN,
                              AttributeDescription.valueOf("cn"),
                              ByteString.valueOfUtf8("PWReset Target"));
    compareOperation.run();
    if (hasProxyPrivilege)
    {
      assertEquals(compareOperation.getResultCode(), COMPARE_TRUE);
    }
    else
    {
      assertEquals(compareOperation.getResultCode(), AUTHORIZATION_DENIED);
    }


    // Test a search operation against the PWReset Target user.
    SearchRequest request = newSearchRequest(targetDN, SearchScope.BASE_OBJECT).addControl(controls);
    InternalSearchOperation searchOperation = conn.processSearch(request);
    searchOperation.run();
    assertProxyPrivilege(searchOperation.getResultCode(), hasProxyPrivilege);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform DIGEST-MD5 authentication when an
   * anonymous authorization ID is specified with an authentication identity
   * that has sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5AnonymousAuthzIDSuccessfulDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=dn:cn=Privileged User,o=test",
      "-o", "authzid=dn:",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will behave properly when attempting to
   * perform DIGEST-MD5 authentication when an authorization ID equaling the
   * authentication ID is specified with an authentication identity that has
   * sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5SameAuthzIDSuccessfulDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=dn:cn=Privileged User,o=test",
      "-o", "authzid=dn:cn=Privileged User,o=test",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform DIGEST-MD5 authentication when an
   * alternate authorization ID is specified with an authentication identity
   * that has sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5DifferentAuthzIDSuccessfulDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=dn:cn=Privileged User,o=test",
      "-o", "authzid=dn:cn=Unprivileged User,o=test",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform DIGEST-MD5 authentication when an
   * anonymous authorization ID is specified with an authentication identity
   * that has sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5AnonymousAuthzIDSuccessfulUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=u:privileged.user",
      "-o", "authzid=u:",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will behave properly when attempting to
   * perform DIGEST-MD5 authentication when an authorization ID equaling the
   * authentication ID is specified with an authentication identity that has
   * sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5SameAuthzIDSuccessfulUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=u:privileged.user",
      "-o", "authzid=u:privileged.user",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform DIGEST-MD5 authentication when an
   * alternate authorization ID is specified with an authentication identity
   * that has sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5DifferentAuthzIDSuccessfulUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=u:privileged.user",
      "-o", "authzid=u:unprivileged.user",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform DIGEST-MD5 authentication when an
   * anonymous authorization ID is specified with an authentication identity
   * that does not have sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5AnonymousAuthzIDFailedDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=dn:cn=Unprivileged User,o=test",
      "-o", "authzid=dn:",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertNotEquals(runSearch(args), 0);
  }



  /**
   * Tests to ensure that the server will behave properly when attempting to
   * perform DIGEST-MD5 authentication when an authorization ID equaling the
   * authentication ID is specified with an authentication identity that does
   * not have sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5SameUnprivAuthzIDSuccessfulDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=dn:cn=Unprivileged User,o=test",
      "-o", "authzid=dn:cn=Unprivileged User,o=test",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform DIGEST-MD5 authentication when an
   * alternate authorization ID is specified with an authentication identity
   * that does not have sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5DifferentAuthzIDFailedDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=dn:cn=Unprivileged User,o=test",
      "-o", "authzid=dn:cn=Privileged User,o=test",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertNotEquals(runSearch(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform DIGEST-MD5 authentication when an
   * anonymous authorization ID is specified with an authentication identity
   * that does not have sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5AnonymousAuthzIDFailedUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=u:unprivileged.user",
      "-o", "authzid=u:",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertNotEquals(runSearch(args), 0);
  }



  /**
   * Tests to ensure that the server will behave properly when attempting to
   * perform DIGEST-MD5 authentication when an authorization ID equaling the
   * authentication ID is specified with an authentication identity that does
   * not have sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5SameUnprivAuthzIDSuccessfulUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=u:unprivileged.user",
      "-o", "authzid=u:unprivileged.user",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform DIGEST-MD5 authentication when an
   * alternate authorization ID is specified with an authentication identity
   * that does not have sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDIGESTMD5DifferentAuthzIDFailedUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=DIGEST-MD5",
      "-o", "authid=u:unprivileged.user",
      "-o", "authzid=u:privileged.user",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertNotEquals(runSearch(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform PLAIN authentication when an
   * anonymous authorization ID is specified with an authentication identity
   * that has sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINAnonymousAuthzIDSuccessfulDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=dn:cn=Privileged User,o=test",
      "-o", "authzid=dn:",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will behave properly when attempting to
   * perform PLAIN authentication when an authorization ID equaling the
   * authentication ID is specified with an authentication identity that has
   * sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINSameAuthzIDSuccessfulDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=dn:cn=Privileged User,o=test",
      "-o", "authzid=dn:cn=Privileged User,o=test",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform PLAIN authentication when an
   * alternate authorization ID is specified with an authentication identity
   * that has sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINDifferentAuthzIDSuccessfulDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=dn:cn=Privileged User,o=test",
      "-o", "authzid=dn:cn=Unprivileged User,o=test",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform PLAIN authentication when an
   * anonymous authorization ID is specified with an authentication identity
   * that has sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINAnonymousAuthzIDSuccessfulUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=u:privileged.user",
      "-o", "authzid=u:",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will behave properly when attempting to
   * perform PLAIN authentication when an authorization ID equaling the
   * authentication ID is specified with an authentication identity that has
   * sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINSameAuthzIDSuccessfulUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=u:privileged.user",
      "-o", "authzid=u:privileged.user",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform PLAIN authentication when an
   * alternate authorization ID is specified with an authentication identity
   * that has sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINDifferentAuthzIDSuccessfulUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=u:privileged.user",
      "-o", "authzid=u:unprivileged.user",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform PLAIN authentication when an
   * anonymous authorization ID is specified with an authentication identity
   * that does not have sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINAnonymousAuthzIDFailedDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=dn:cn=Unprivileged User,o=test",
      "-o", "authzid=dn:",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertNotEquals(runSearch(args), 0);
  }



  /**
   * Tests to ensure that the server will behave properly when attempting to
   * perform PLAIN authentication when an authorization ID equaling the
   * authentication ID is specified with an authentication identity that does
   * not have sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINSameUnprivAuthzIDSuccessfulDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=dn:cn=Unprivileged User,o=test",
      "-o", "authzid=dn:cn=Unprivileged User,o=test",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform PLAIN authentication when an
   * alternate authorization ID is specified with an authentication identity
   * that does not have sufficient privileges and using the "dn:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINDifferentAuthzIDFailedDNColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=dn:cn=Unprivileged User,o=test",
      "-o", "authzid=dn:cn=Privileged User,o=test",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertNotEquals(runSearch(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform PLAIN authentication when an
   * anonymous authorization ID is specified with an authentication identity
   * that does not have sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINAnonymousAuthzIDFailedUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=u:unprivileged.user",
      "-o", "authzid=u:",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertNotEquals(runSearch(args), 0);
  }



  /**
   * Tests to ensure that the server will behave properly when attempting to
   * perform PLAIN authentication when an authorization ID equaling the
   * authentication ID is specified with an authentication identity that does
   * not have sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINSameUnprivAuthzIDSuccessfulUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=u:unprivileged.user",
      "-o", "authzid=u:unprivileged.user",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertEquals(runSearchWithSystemErr(args), 0);
  }



  /**
   * Tests to ensure that the server will properly respect the PROXIED_AUTH
   * privilege when attempting to perform PLAIN authentication when an
   * alternate authorization ID is specified with an authentication identity
   * that does not have sufficient privileges and using the "u:" syntax.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testPLAINDifferentAuthzIDFailedUColon()
         throws Exception
  {
    String[] args =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=u:unprivileged.user",
      "-o", "authzid=u:privileged.user",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertNotEquals(runSearch(args), 0);
  }



  /**
   * Tests the ability to disable a privilege so that an operation which will
   * fail for a user without an appropriate privilege will succeed if that
   * privilege is disabled.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testDisablePrivilege()
         throws Exception
  {
    // Make sure that the operation fails when the privilege is not disabled.
    String[] searchArgs =
    {
      "--noPropertiesFile",
      "-h", "127.0.0.1",
      "-p", String.valueOf(TestCaseUtils.getServerLdapPort()),
      "-o", "mech=PLAIN",
      "-o", "authid=dn:cn=Directory Manager",
      "-o", "authzid=u:privileged.user",
      "-w", "password",
      "-b", "o=test",
      "-s", "base",
      "(objectClass=*)"
    };

    assertNotEquals(runSearch(searchArgs), 0);


    // Disable the PROXIED_AUTH privilege and verify that the operation now
    // succeeds.
    TestCaseUtils.dsconfig(
      "set-global-configuration-prop",
      "--add", "disabled-privilege:proxied-auth");
    assertEquals(runSearch(searchArgs), 0);


    // Re-enable the PROXIED_AUTH privilege and verify that the operation
    // fails again.
    TestCaseUtils.dsconfig(
      "set-global-configuration-prop",
      "--remove", "disabled-privilege:proxied-auth");

    assertNotEquals(runSearch(searchArgs), 0);
  }

  private int runSearch(String[] args)
  {
    return LDAPSearch.run(nullPrintStream(), nullPrintStream(), args);
  }

  private int runSearchWithSystemErr(String[] args)
  {
    return LDAPSearch.run(nullPrintStream(), System.err, args);
  }

  /**
   * Tests the ability to update the set of privileges for a user on the fly
   * and have them take effect immediately.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testUpdateUserPrivileges()
         throws Exception
  {
    InternalClientConnection rootConnection =
         InternalClientConnection.getRootConnection();

    TestCaseUtils.addEntry(
      "dn: cn=Test User,o=test",
      "objectClass: top",
      "objectClass: person",
      "objectClass: organizationalPerson",
      "objectClass: inetOrgPerson",
      "cn: Test User",
      "givenName: Test",
      "sn: User",
      "userPassword: password");

    // We won't use an internal connection here because these are not notified
    // of dynamic changes to authentication info.
    try (RemoteConnection conn = new RemoteConnection("localhost", TestCaseUtils.getServerLdapPort()))
    {
      conn.bind("cn=Test User,o=test", "password");

      CopyOnWriteArraySet<ClientConnection> connections = DirectoryServer
          .getAuthenticatedUsers().get(DN.valueOf("cn=Test User,o=test"));

      assertNotNull(connections);
      assertEquals(connections.size(), 1);
      ClientConnection testConnection = connections.iterator().next();

      // Make sure the user starts out without any privileges.
      for (Privilege p : Privilege.values())
      {
        assertFalse(testConnection.hasPrivilege(p, null));
      }

      // Modify the user entry to add the CONFIG_READ privilege and verify that
      // the client connection reflects that.
      ModifyRequest modifyRequest = newModifyRequest("cn=Test User,o=test")
          .addModification(ADD, "ds-privilege-name", "config-read");
      ModifyOperation modifyOperation = rootConnection.processModify(modifyRequest);
      assertEquals(modifyOperation.getResultCode(), ResultCode.SUCCESS);
      assertTrue(testConnection.hasPrivilege(Privilege.CONFIG_READ, null));

      // Take the privilege away from the user and verify that it is recognized
      // immediately.
      modifyRequest = newModifyRequest("cn=Test User,o=test")
          .addModification(DELETE, "ds-privilege-name", "config-read");
      modifyOperation = rootConnection.processModify(modifyRequest);
      assertEquals(modifyOperation.getResultCode(), ResultCode.SUCCESS);
      assertFalse(testConnection.hasPrivilege(Privilege.CONFIG_READ, null));

      DeleteOperation deleteOperation = rootConnection.processDelete("cn=Test User,o=test");
      assertEquals(deleteOperation.getResultCode(), ResultCode.SUCCESS);
    }
  }



  /**
   * Tests the ability to update the set of root privileges and have them take
   * effect immediately for new root connections.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @Test
  public void testUpdateRootPrivileges() throws Exception
  {
    // Make sure that a root connection doesn't  have the proxied auth
    // privilege.
    DN unprivRootDN = DN.valueOf("cn=Unprivileged Root,cn=Root DNs,cn=config");
    Entry unprivRootEntry = DirectoryServer.getEntry(unprivRootDN);
    AuthenticationInfo authInfo = new AuthenticationInfo(unprivRootEntry, true);
    InternalClientConnection unprivRootConn =
         new InternalClientConnection(authInfo);
    assertFalse(unprivRootConn.hasPrivilege(Privilege.PROXIED_AUTH, null));


    // Update the set of root privileges to include proxied auth.
    InternalClientConnection internalRootConn = getRootConnection();

    ModifyRequest modifyRequest = newModifyRequest("cn=Root DNs,cn=config")
        .addModification(ADD, "ds-cfg-default-root-privilege-name", "proxied-auth");
    ModifyOperation modifyOperation = internalRootConn.processModify(modifyRequest);
    assertEquals(modifyOperation.getResultCode(), ResultCode.SUCCESS);


    // Get a new root connection and verify that it now has proxied auth.
    unprivRootEntry = DirectoryServer.getEntry(unprivRootDN);
    authInfo = new AuthenticationInfo(unprivRootEntry, true);
    unprivRootConn = new InternalClientConnection(authInfo);
    assertTrue(unprivRootConn.hasPrivilege(Privilege.PROXIED_AUTH, null));


    // Update the set of root privileges to revoke proxied auth.
    modifyRequest = newModifyRequest("cn=Root DNs,cn=config")
        .addModification(DELETE, "ds-cfg-default-root-privilege-name", "proxied-auth");
    modifyOperation = internalRootConn.processModify(modifyRequest);
    assertEquals(modifyOperation.getResultCode(), ResultCode.SUCCESS);


    // Get a new root connection and verify that it no longer has proxied auth.
    unprivRootEntry = DirectoryServer.getEntry(unprivRootDN);
    authInfo = new AuthenticationInfo(unprivRootEntry, true);
    unprivRootConn = new InternalClientConnection(authInfo);
    assertFalse(unprivRootConn.hasPrivilege(Privilege.PROXIED_AUTH, null));
  }



  /**
   * Tests to ensure that the set of privileges defined in the server matches
   * the set of privileges that can be configured as default root privileges,
   * and that it also matches the set of privileges that can be disabled.
   */
  @Test
  public void testConfigurablePrivilegeSets()
  {
    Set<String> serverPrivNames = new HashSet<>();
    for (Privilege p : Privilege.values())
    {
      serverPrivNames.add(p.toString());
    }

    Set<String> defaultRootPrivNames = new HashSet<>();
    for (RootDNCfgDefn.DefaultRootPrivilegeName p :
         RootDNCfgDefn.DefaultRootPrivilegeName.values())
    {
      defaultRootPrivNames.add(p.toString());
      assertTrue(serverPrivNames.contains(p.toString()),
                 "The set of server privileges does not contain potential " +
                 "default root privilege " + p);
    }

    Set<String> disableablePrivNames = new HashSet<>();
    for (DisabledPrivilege p : DisabledPrivilege.values())
    {
      disableablePrivNames.add(p.toString());
      assertTrue(serverPrivNames.contains(p.toString()),
                 "The set of server privileges does not contain disableable " +
                 "privilege " + p);
    }

    for (String s : serverPrivNames)
    {
      assertTrue(defaultRootPrivNames.contains(s),
                 "The set of available default root privileges does not " +
                 "contain server privilege " + s);

      assertTrue(disableablePrivNames.contains(s),
                 "The set of disableable privileges does not contain server " +
                 "privilege " + s);
    }
  }



  /**
   * Retrieves the specified task from the server, waiting for it to finish all
   * the running its going to do before returning.
   *
   * @param  taskEntryDN  The DN of the entry for the task to retrieve.
   *
   * @return  The requested task entry.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  private Task getCompletedTask(DN taskEntryDN) throws Exception
  {
    TaskBackend taskBackend = (TaskBackend)
        TestCaseUtils.getServerContext().getBackendConfigManager().findLocalBackendForEntry(DN.valueOf("cn=tasks"));
    Task task = taskBackend.getScheduledTask(taskEntryDN);
    if (task == null)
    {
      long stopWaitingTime = System.currentTimeMillis() + 10000L;
      while (task == null && System.currentTimeMillis() < stopWaitingTime)
      {
        Thread.sleep(10);
        task = taskBackend.getScheduledTask(taskEntryDN);
      }
    }

    assertNotNull(task, "There is no such task " + taskEntryDN);

    if (! TaskState.isDone(task.getTaskState()))
    {
      long stopWaitingTime = System.currentTimeMillis() + 20000L;
      while (!TaskState.isDone(task.getTaskState())
          && System.currentTimeMillis() < stopWaitingTime)
      {
        Thread.sleep(10);
      }
    }
    assertTrue(TaskState.isDone(task.getTaskState()),
        "Task " + taskEntryDN + " did not complete in a timely manner.");

    return task;
  }

  private void processModifyDN(InternalClientConnection conn, String dn, String newRdn, ResultCode expectedRC)
  {
    ModifyDNOperation op = conn.processModifyDN(newModifyDNRequest(dn, newRdn).setDeleteOldRDN(true));
    assertEquals(op.getResultCode(), expectedRC);
  }
}
