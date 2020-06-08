/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.service;

import com.linkedin.kube2hadoop.core.Constants;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import org.apache.directory.server.kerberos.shared.keytab.Keytab;
import org.apache.directory.server.kerberos.shared.keytab.KeytabEntry;
import org.apache.directory.shared.kerberos.KerberosTime;
import org.apache.directory.shared.kerberos.codec.types.EncryptionType;
import org.apache.directory.shared.kerberos.components.EncryptionKey;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.testng.Assert;
import org.testng.annotations.Test;


public class TestTokenFetcherService {
  @Test
  public void testGetPrincipalFromKeytab() throws IOException {
    String keytabLocation = "dummy.keytab";
    String expectedPrincipalName = "somedomain/somehost";
    String fullPrincipalName = expectedPrincipalName + "@somecompany.com";

    Keytab kt = new Keytab();
    KeytabEntry kte = new KeytabEntry(fullPrincipalName, 0,
        new KerberosTime(), (byte) (0), new EncryptionKey(EncryptionType.AES256_CTS_HMAC_SHA1_96, new byte[0]));
    kt.setEntries(Arrays.asList(kte));
    File keytabFile = new File(keytabLocation);
    kt.write(keytabFile);

    String actualPrincipalName = TokenFetcherService.getPrincipalFromKeytab(keytabLocation);
    Assert.assertEquals(actualPrincipalName, expectedPrincipalName);

    keytabFile.delete();
  }

  @Test
  public void testOneEncodeCredentialsToBase64Str() throws IOException {
    Credentials cred = new Credentials();
    String tokenName = "testToken";
    addTokenToCredential(cred, tokenName);
    String encodedStr = TokenFetcherService.encodeCredentialsToBase64(cred);
    Credentials decodedCred = decodeStrToCredentials(encodedStr);

    Assert.assertEquals(cred.getToken(new Text(tokenName)).getKind().toString(),
        decodedCred.getToken(new Text(tokenName)).getKind().toString());
    Assert.assertEquals(cred.getToken(new Text(tokenName)).getIdentifier(),
        decodedCred.getToken(new Text(tokenName)).getIdentifier());
    Assert.assertEquals(cred.getToken(new Text(tokenName)).getService().toString(),
        decodedCred.getToken(new Text(tokenName)).getService().toString());
    Assert.assertEquals(cred.numberOfTokens(), decodedCred.numberOfTokens());
  }

  @Test
  public void testTwoEncodedCredentialsToBase64Str() throws IOException {
    Credentials cred = new Credentials();
    String tokenName1 = "testToken1";
    String tokenName2 = "testToken2";

    addTokenToCredential(cred, tokenName1);
    addTokenToCredential(cred, tokenName2);

    String encodedStr = TokenFetcherService.encodeCredentialsToBase64(cred);
    Credentials decodedCred = decodeStrToCredentials(encodedStr);

    Assert.assertEquals(cred.getToken(new Text(tokenName1)).getKind().toString(),
        decodedCred.getToken(new Text(tokenName1)).getKind().toString());
    Assert.assertEquals(cred.getToken(new Text(tokenName1)).getIdentifier(),
        decodedCred.getToken(new Text(tokenName1)).getIdentifier());
    Assert.assertEquals(cred.getToken(new Text(tokenName1)).getService().toString(),
        decodedCred.getToken(new Text(tokenName1)).getService().toString());

    Assert.assertEquals(cred.getToken(new Text(tokenName2)).getKind().toString(),
        decodedCred.getToken(new Text(tokenName2)).getKind().toString());
    Assert.assertEquals(cred.getToken(new Text(tokenName2)).getIdentifier(),
        decodedCred.getToken(new Text(tokenName2)).getIdentifier());
    Assert.assertEquals(cred.getToken(new Text(tokenName2)).getService().toString(),
        decodedCred.getToken(new Text(tokenName2)).getService().toString());

    Assert.assertEquals(cred.numberOfTokens(), decodedCred.numberOfTokens());
  }

  private void addTokenToCredential(Credentials cred, String tokenName) {
    Token<TokenIdentifier> token =
        new Token<>(new byte[0], new byte[0], new Text(Constants.HDFS_DELEGATION_TOKEN), new Text());
    cred.addToken(new Text(tokenName), token);
  }


  private Credentials decodeStrToCredentials(String str) throws IOException {
    byte[] credBytes = Base64.getDecoder().decode(str);
    Credentials cred = new Credentials();
    InputStream is = new ByteArrayInputStream(credBytes);

    cred.readTokenStorageStream(new DataInputStream(is));

    return cred;
  }
}
