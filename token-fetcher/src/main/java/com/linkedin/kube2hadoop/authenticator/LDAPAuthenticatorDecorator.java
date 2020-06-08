/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.authenticator;

import com.linkedin.kube2hadoop.core.Constants;
import com.linkedin.kube2hadoop.core.ErrorCode;
import com.linkedin.kube2hadoop.core.TokenServiceException;
import com.linkedin.kube2hadoop.core.conf.ConfigurationKeys;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchResult;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;


public class LDAPAuthenticatorDecorator implements Authenticator {
  private static final Log LOG = LogFactory.getLog(LDAPAuthenticatorDecorator.class);
  private Authenticator authenticator;

  /**
   * Authenticate user to access headless account.
   * If user instructed to authenticate via headless account, check if they belong to the headless account
   * and authenticate. Otherwise, pass authenticated user from base Authenticator.
   * @param params Parameters optionally containing headless account id
   * @return Headless account id if pass authentication; previously authenticated user if no params found for headless
   *  account authentication
   */
  @Override
  public String getAuthenticatedUserID(AuthenticatorParameters params) {
    String authenticatedUser = authenticator.getAuthenticatedUserID(params);
    String doAs = params.getParamString(Constants.DO_AS);
    if (doAs != null) {
      // LDAP lookup
      List<String> headlessAccountUsers = fetchUsersBehindHeadlessAccountFromLDAP(doAs);

      if (headlessAccountUsers.contains(authenticatedUser)) {
        LOG.info("Successfully authenticated user: " + authenticatedUser + " to access headless account: " + doAs);
        return doAs;
      } else {
        String errorMsg = "Failed to authenticate user: " + authenticatedUser + " to access headless account: " + doAs;
        LOG.error(errorMsg);
        throw new TokenServiceException(errorMsg, ErrorCode.LDAP_LOOKUP_EXCEPTION);
      }
    }

    LOG.info("Cannot find doAs annotation, authenticate as user itself: " + authenticatedUser);
    return authenticatedUser;
  }

  @Override
  public Configuration getConfiguration() {
    return authenticator.getConfiguration();
  }

  LDAPAuthenticatorDecorator(Authenticator authenticator) {
    super();
    this.authenticator = authenticator;
  }

  private List<String> fetchUsersBehindHeadlessAccountFromLDAP(String uid) {
    LOG.info("Fetch headless users from LDAP for " + uid);
    List<String> users = new ArrayList<>();

    Attributes attr = new BasicAttributes();
    attr.put(Constants.CN_ATTR, uid);

    DirContext ctx = null;
    try {
      ctx = new InitialDirContext(createLdapEnv());

      String groupDomain = getConfiguration().get(ConfigurationKeys.KUBE2HAdOOP_AUTHENTICATOR_LDAP_GROUP_DOMAIN);
      NamingEnumeration<SearchResult> results = ctx.search(groupDomain, attr);
      if (!results.hasMore()) {
        throw new NamingException();
      }
      while (results.hasMore()) {
        Attribute result = results.next().getAttributes().get(Constants.MEM_UID_ATTR);
        if (result != null) {
          for (int i = 0; i < result.size(); i++) {
            String account = result.get(i).toString();
            users.add(account);
          }
        }
      }
    } catch (NamingException e) {
      LOG.error("Users not found. ", e);
    } finally {
      if (ctx != null) {
        try {
          ctx.close();
        } catch (NamingException e) {
          LOG.error("Encountered exception while closing context", e);
        }
      }
    }
    return users;
  }

  private Hashtable<String, String> createLdapEnv() {
    Hashtable<String, String> env = new Hashtable<>();
    String ldapProviderURL = getConfiguration().get(ConfigurationKeys.KUBE2HADOOP_AUTHENTICATOR_LDAP_PROVIDER_URL);
    env.put(Context.INITIAL_CONTEXT_FACTORY, Constants.CONTEXT_FACTORY);
    env.put(Context.PROVIDER_URL, ldapProviderURL);
    return env;
  }
}
