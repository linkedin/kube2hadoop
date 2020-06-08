/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.service;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.linkedin.kube2hadoop.authenticator.Authenticator;
import com.linkedin.kube2hadoop.authenticator.AuthenticatorFactory;
import com.linkedin.kube2hadoop.authenticator.AuthenticatorParameters;
import com.linkedin.kube2hadoop.cache.LocalTokenCache;
import com.linkedin.kube2hadoop.cache.TokenCache;
import com.linkedin.kube2hadoop.cache.TokenInfo;
import com.linkedin.kube2hadoop.core.Constants;
import com.linkedin.kube2hadoop.core.ErrorCode;
import com.linkedin.kube2hadoop.core.TokenServiceException;
import com.linkedin.kube2hadoop.core.conf.ConfigurationKeys;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.directory.server.kerberos.shared.keytab.Keytab;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;

import org.apache.log4j.Logger;


/**
 * Responsible for fetching hadoop delegation token on behalf of the user
 */
public class TokenFetcherService {
  private static volatile TokenFetcherService tfsInstance = null;
  private static final Logger LOG = Logger.getLogger(TokenFetcherService.class);
  public static final List<String> SUPPORTED_TOKEN_KINDS = ImmutableList.of(Constants.HDFS_DELEGATION_TOKEN);

  private final Configuration conf;
  private UserGroupInformation loginUser;
  private Map<String, UserGroupInformation> userUgiCache = new HashMap<>();
  private Authenticator authenticator;
  private TokenCache tokenCache;
  private String tokenRenewer;

  /**
   * {@code TokenFetcherService} constructor
   * Use Kerberos keytab to login to/authenticate with Hadoop.
   * Creates proper {@code Authenticator} from configuration
   * @param conf kube2hadoop configuration
   */
  private TokenFetcherService(final Configuration conf) throws TokenServiceException {
    this.conf = conf;
    String keytabLocation = conf.getRaw(ConfigurationKeys.KUBE2HADOOP_KEYTAB_LOCATION);
    String keytabPrincipal = getPrincipalFromKeytab(keytabLocation);
    tokenRenewer = conf.get(ConfigurationKeys.KUBE2HADOOP_RENEWER_NAME,
        ConfigurationKeys.DEFAULT_KUBE2HADOOP_RENEWER_NAME);

    String hadoopConfDir = conf.get(ConfigurationKeys.HADOOP_CONF_DIR, null);
    if (hadoopConfDir == null) {
      hadoopConfDir = System.getenv(Constants.HADOOP_CONF_DIR);
    }

    // Add Hadoop configuration
    this.conf.addResource(new Path(hadoopConfDir + File.separatorChar + Constants.CORE_SITE_CONF));
    this.conf.addResource(new Path(hadoopConfDir + File.separatorChar + Constants.HDFS_SITE_CONF));

    UserGroupInformation.setConfiguration(this.conf);

    // Temporary solution to upstream ticket: HADOOP-12954. Token service must use hostname to establish connection.
    SecurityUtil.setTokenServiceUseIp(false);

    // try login
    try {
      LOG.info("Creating login user");
      LOG.info("Using keytab principal: " + keytabPrincipal + " from: "
          + keytabLocation);
      UserGroupInformation.loginUserFromKeytab(keytabPrincipal,
          keytabLocation);
      this.loginUser = UserGroupInformation.getLoginUser();
      LOG.info("Logged in with user " + this.loginUser);
    } catch (final IOException e) {
      throw new TokenServiceException(
          "Failed to login with kerberos: " + e.getMessage(), ErrorCode.FAILED_TO_LOGIN_WITH_KERBEROS);
    }

    // TODO: read authenticator configurations
    AuthenticatorFactory authFactory = new AuthenticatorFactory();
    authenticator = authFactory.getAuthenticator(ConfigurationKeys.AuthenticationPlatform.KUBERNETES,
        Arrays.asList(ConfigurationKeys.AuthenticationDecorators.LDAP), conf);

    // TODO: read configuration to determine the type of cache to initialize
    LOG.info("Initializing LocalTokenCache...");
    tokenCache = new LocalTokenCache(conf);
  }

  public static TokenFetcherService getInstance(Configuration conf) throws TokenServiceException {
    if (tfsInstance == null) {
      synchronized (TokenFetcherService.class) {
        if (tfsInstance == null) {
          LOG.info("Getting new TokenFetcherService instance");
          tfsInstance = new TokenFetcherService(conf);
        }
      }
    }

    return tfsInstance;
  }

  /**
   * Read the keytab file and retrieve the keytab's principal name
   * @param keytabLocation keytab file location
   * @return keytab's principal name
   */
  static String getPrincipalFromKeytab(String keytabLocation) {
    try {
      return Keytab
          .read(new File(keytabLocation))
          .getEntries().get(0)
          .getPrincipalName()
          .split("@")[0]
          .replace('\\', '/');
    } catch (Exception ex) {
      throw new TokenServiceException(ex.getMessage(), ErrorCode.READ_KEYTAB_EXCEPTION);
    }
  }

  /**
   * Get Delegation Token
   * @param params parameters from http request
   * @return base64 encoded credential
   * @throws TokenServiceException Any exception from authentication step or fetching delegation token step
   */
  public String getDelegationTokens(Map<String, String[]> params) throws TokenServiceException {
    if (!validateParams(params)) {
      throw new TokenServiceException(ErrorCode.INVALID_PARAMS.getDescription(), ErrorCode.INVALID_PARAMS);
    }

    Credentials cred = new Credentials();

    String userToProxy = authenticator.getAuthenticatedUserID(new AuthenticatorParameters(params));
    LOG.info("User to proxy is: " + userToProxy);

    goFetchDelegationTokens(userToProxy, params.get(Constants.TOKEN_KINDS), cred);

    // Add the delegation tokens to {@code TokenCache}
    List<TokenInfo> tokenInfos = TokenInfo.getTokenInfos(ImmutableMap.of(
        Constants.NAMESPACE, params.get(Constants.NAMESPACE)[0],
        Constants.POD_NAME, params.get(Constants.POD_NAME)[0],
        Constants.PROXY_USER, userToProxy),
        new ArrayList<>(cred.getAllTokens()));
    tokenInfos.forEach(tokenInfo -> tokenCache.addToken(tokenInfo));

    return encodeCredentialsToBase64(cred);
  }

  /**
   * Verify that incoming requests have all the required field, and all required field and optional field
   * have correct format
   * @param params parameters from http request
   * @return boolean, whether parameters pass validation
   */
  private boolean validateParams(Map<String, String[]> params) {
//    if (podName == null || tokenKinds == null || tokenKinds.length == 0) {
//      ErrorCode errCode = ErrorCode.INVALID_PARAMS;
//      throw new TokenServiceException(errCode.getDescription(), errCode);
//    }
    // TODO: validate parameters based on authentication methods and platform
    return true;
  }

  private void goFetchDelegationTokens(final String userToProxy, String[] tokenKinds, Credentials cred) {
    for (String tokenKind : tokenKinds) {
      if (!SUPPORTED_TOKEN_KINDS.contains(tokenKind)) {
        throw new TokenServiceException("Unsupported token kind: " + tokenKind, ErrorCode.UNSUPPORTED_TOKEN_KIND);
      }
    }

    for (String tokenKind : tokenKinds) {
      if (tokenKind.equals(Constants.HDFS_DELEGATION_TOKEN)) {
        fetchDelegationTokenViaSuperUser(userToProxy, cred);
        LOG.info("Fetched HDFS Delegation Token");
      }
    }
  }

  private void fetchDelegationTokenViaSuperUser(final String userToProxy, final Credentials cred) {
    try {
      UserGroupInformation proxyUgi = getProxiedUser(userToProxy);
      LOG.info("Proxy Ugi for " + userToProxy + ": " + proxyUgi.toString());

      proxyUgi.doAs(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception {
          LOG.debug("Fetching delegation token as privileged user");
          getToken(userToProxy);
          return null;
        }

        private void getToken(final String userToProxy) throws IOException, TokenServiceException {
          fetchNameNodeToken(userToProxy, cred);
        }
      });
    } catch (final Exception e) {
      throw new TokenServiceException("Failed to get hadoop tokens! "
          + e.getMessage() + e.getCause(), ErrorCode.FAILED_TO_FETCH_HDFS_TOKEN);
    } catch (final Throwable t) {
      throw new TokenServiceException("Failed to get hadoop tokens! "
          + t.getMessage() + t.getCause(), ErrorCode.FAILED_TO_FETCH_HDFS_TOKEN);
    }
  }

  /**
   * Create a proxied user based on the explicit user name
   * @param userToProxy user to proxy as
   * @return UserGroupInformation
   * @throws TokenServiceException throws exception when failed to create the proxy user
   */
  private synchronized UserGroupInformation getProxiedUser(final String userToProxy)
      throws TokenServiceException {
    UserGroupInformation ugi = this.userUgiCache.get(userToProxy);
    if (ugi == null) {
      LOG.info("proxy user " + userToProxy
          + " not exist. Creating new proxy user");
      try {
        ugi =
            UserGroupInformation.createProxyUser(userToProxy,
                UserGroupInformation.getLoginUser());
        LOG.info("ugi username: " + ugi.getUserName());
      } catch (final IOException e) {
        throw new TokenServiceException(e.toString(), ErrorCode.FAILED_TO_CREATE_PROXY_USER);
      }
      this.userUgiCache.putIfAbsent(userToProxy, ugi);
    }
    return ugi;
  }

  private void fetchNameNodeToken(String userToProxy, Credentials cred) throws IOException {
    final FileSystem fs = FileSystem.get(TokenFetcherService.this.conf);
    // check if we get the correct FS, and most importantly, the conf
    LOG.info("Getting DFS token from " + fs.getUri());

    final Token<?>[] fsTokens =
        fs.addDelegationTokens(tokenRenewer, cred);

    if (fsTokens.length == 0) {
      throw new TokenServiceException(
          "Failed to fetch HDFS token for " + userToProxy, ErrorCode.FAILED_TO_FETCH_HDFS_TOKEN);
    }

    for (final Token<?> fsToken : fsTokens) {
      LOG.info(String.format(
          "DFS token from namenode fetched, token kind: %s, token service: %s",
          fsToken.getKind(), fsToken.getService()));
    }

    //TODO: getting additional name nodes tokens
//    if ((nameNodeAddr != null) && (nameNodeAddr.length() > 0)) {
//      LOG.info("Fetching token(s) for other namenode(s): " + nameNodeAddr);
//      System.out.println("Fetching token(s) for other namenode(s): " + nameNodeAddr);
//      final String[] nameNodeArr = nameNodeAddr.split(",");
//      final Path[] ps = new Path[nameNodeArr.length];
//      for (int i = 0; i < ps.length; i++) {
//        ps[i] = new Path(nameNodeArr[i].trim());
//      }
//      TokenCache.obtainTokensForNamenodes(cred, ps, this.conf);
//      LOG.info("Successfully fetched tokens for: " + nameNodeAddr);
//    } else {
//      LOG.info(
//          "Other name node was not configured");
//    }
  }

  /**
   * Base64 encoded credentials object for easy transmission over http
   * @param cred Credential object, can contain multiple kinds of tokens
   * @return base64 encoded credential
   */
   static String encodeCredentialsToBase64(Credentials cred) {
    ByteArrayOutputStream baos = null;
    DataOutputStream dos = null;

    try {
      baos = new ByteArrayOutputStream();
      dos = new DataOutputStream(baos);
      cred.writeTokenStorageToStream(dos);
      cred.getAllTokens().forEach(x -> LOG.info(x.toString()));
    } catch (IOException ioe) {
      throw new TokenServiceException(ioe.toString(), ErrorCode.CREDENTIAL_ENCODING_EXCEPTION);
    } finally {
      if (dos != null) {
        try {
          dos.close();
        } catch (final Throwable t) {
          LOG.error("encountered exception while closing DataOutputStream", t);
        }
      }
      if (baos != null) {
        try {
          baos.close();
        } catch (final Throwable t) {
          LOG.error("encountered exception while closing ByteArrayOutputStream", t);
        }
      }
    }

    return Base64.getEncoder().encodeToString(baos.toByteArray());
  }
}
