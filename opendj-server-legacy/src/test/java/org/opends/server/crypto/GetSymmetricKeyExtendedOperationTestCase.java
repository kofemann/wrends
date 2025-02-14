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
 * Copyright 2008 Sun Microsystems, Inc.
 * Portions Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.server.crypto;

import static org.opends.server.TestCaseUtils.assertNotEquals;
import static org.opends.server.config.ConfigConstants.ATTR_CRYPTO_CIPHER_TRANSFORMATION_NAME;
import static org.opends.server.config.ConfigConstants.ATTR_CRYPTO_KEY_COMPROMISED_TIME;
import static org.opends.server.config.ConfigConstants.ATTR_CRYPTO_KEY_LENGTH_BITS;
import static org.opends.server.config.ConfigConstants.ATTR_CRYPTO_SYMMETRIC_KEY;
import static org.opends.server.config.ConfigConstants.OC_CRYPTO_CIPHER_KEY;
import static org.opends.server.protocols.internal.InternalClientConnection.getRootConnection;
import static org.opends.server.protocols.internal.Requests.newSearchRequest;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.SearchScope;
import org.opends.admin.ads.ADSContext;
import org.opends.server.TestCaseUtils;
import org.opends.server.config.ConfigConstants;
import org.opends.server.core.DirectoryServer;
import org.opends.server.core.ExtendedOperation;
import org.opends.server.protocols.internal.InternalClientConnection;
import org.opends.server.protocols.internal.InternalSearchOperation;
import org.opends.server.protocols.internal.SearchRequest;
import org.opends.server.types.Entry;
import org.opends.server.util.ServerConstants;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * A set of test cases for the symmetric key extended operation.
 */
@SuppressWarnings("javadoc")
public class GetSymmetricKeyExtendedOperationTestCase
     extends CryptoTestCase {
  /**
   * Ensures that the Directory Server is running.
   *
   * @throws  Exception  If an unexpected problem occurs.
   */
  @BeforeClass
  public void startServer()
         throws Exception
  {
    TestCaseUtils.startServer();
  }



  @Test(enabled=true)
  public void testValidRequest() throws Exception
  {
    final CryptoManagerImpl cm = DirectoryServer.getCryptoManager();
    final String secretMessage = "zyxwvutsrqponmlkjihgfedcba";
    final String cipherTransformationName = "AES/CBC/PKCS5Padding";
    final int cipherKeyLength = 128;

    CryptoManagerImpl.publishInstanceKeyEntryInADS();

    // Initial encryption ensures a cipher key entry is in ADS.
    cm.encrypt(cipherTransformationName, cipherKeyLength,
            secretMessage.getBytes());

    // Retrieve all uncompromised cipher key entries corresponding to the
    // specified transformation and key length.
    final String baseDNStr // TODO: is this DN defined elsewhere as a constant?
            = "cn=secret keys," + ADSContext.getAdministrationSuffixDN();
    final DN baseDN = DN.valueOf(baseDNStr);
    final String FILTER_OC_INSTANCE_KEY = "(objectclass=" + OC_CRYPTO_CIPHER_KEY + ")";
    final String FILTER_NOT_COMPROMISED = "(!(" + ATTR_CRYPTO_KEY_COMPROMISED_TIME + "=*))";
    final String FILTER_CIPHER_TRANSFORMATION_NAME =
        "(" + ATTR_CRYPTO_CIPHER_TRANSFORMATION_NAME + "=" + cipherTransformationName + ")";
    final String FILTER_CIPHER_KEY_LENGTH = "(" + ATTR_CRYPTO_KEY_LENGTH_BITS + "=" + cipherKeyLength + ")";
    final String searchFilter =
        "(&"
        + FILTER_OC_INSTANCE_KEY
        + FILTER_NOT_COMPROMISED
        + FILTER_CIPHER_TRANSFORMATION_NAME
        + FILTER_CIPHER_KEY_LENGTH
        + ")";
    final SearchRequest request = newSearchRequest(baseDN, SearchScope.SINGLE_LEVEL, searchFilter)
        .addAttribute(ConfigConstants.ATTR_CRYPTO_SYMMETRIC_KEY);
    InternalSearchOperation searchOp = getRootConnection().processSearch(request);
    assertFalse(searchOp.getSearchEntries().isEmpty());

    final InternalClientConnection internalConnection =
         InternalClientConnection.getRootConnection();
    final String instanceKeyID = cm.getInstanceKeyID();
    for (Entry e : searchOp.getSearchEntries()) {
      final String symmetricKeyAttributeValue =
          e.parseAttribute(ATTR_CRYPTO_SYMMETRIC_KEY).asString();
      final ByteString requestValue =
           GetSymmetricKeyExtendedOperation.encodeRequestValue(
                symmetricKeyAttributeValue, instanceKeyID);
      final ExtendedOperation extendedOperation =
              internalConnection.processExtendedOperation(
                      ServerConstants.OID_GET_SYMMETRIC_KEY_EXTENDED_OP,
                      requestValue);
      assertEquals(extendedOperation.getResultCode(), ResultCode.SUCCESS);
      // The key should be re-wrapped, and hence have a different binary
      // representation....
      final String responseValue
              = extendedOperation.getResponseValue().toString();
      assertFalse(symmetricKeyAttributeValue.equals(responseValue));
      // ... but the keyIDs should be equal (ideally, the validity of
      // the returned value would be checked by decoding the
      // returned ds-cfg-symmetric-key attribute value; however, there
      // is no non-private method to call.
      assertEquals(responseValue.split(":")[0],
              symmetricKeyAttributeValue.split(":")[0]);
    }
  }


  @Test
  public void testInvalidRequest() throws Exception
  {
    CryptoManagerImpl cm = DirectoryServer.getCryptoManager();

    String symmetricKey = "1";
    String instanceKeyID = cm.getInstanceKeyID();

    ByteString requestValue =
         GetSymmetricKeyExtendedOperation.encodeRequestValue(
              symmetricKey, instanceKeyID);

    InternalClientConnection internalConnection =
         InternalClientConnection.getRootConnection();
    ExtendedOperation extendedOperation =
         internalConnection.processExtendedOperation(
              ServerConstants.OID_GET_SYMMETRIC_KEY_EXTENDED_OP, requestValue);

    assertNotEquals(extendedOperation.getResultCode(), ResultCode.SUCCESS);
  }
}
