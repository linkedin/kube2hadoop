/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.service;

import com.linkedin.kube2hadoop.cache.TokenCache;
import com.linkedin.kube2hadoop.cache.TokenInfo;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.TimerTask;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.SecretManager;


/**
 * Token Renewal Task that runs in a scheduled interval
 */
public class TokenRenewalService extends TimerTask {
  private static final Log LOG = LogFactory.getLog(TokenRenewalService.class);
  private Configuration conf;
  private TokenCache tokenCache;

  public TokenRenewalService(Configuration conf, TokenCache tokenCache) {
    this.conf = conf;
    this.tokenCache = tokenCache;
  }

  public void run() {
    List<TokenInfo> tokensToRenew = tokenCache.getTokensForRenewal();
    LOG.info("TokenRenewalService finished scanning, found " + tokensToRenew.size() + " tokens to renew.");
    for (TokenInfo tokenInfo : tokensToRenew) {
      try {
        renewDelegationToken(tokenInfo);
        LOG.debug("Successfully renewed token for user: " + tokenInfo.getTokenOwner());
      } catch (SecretManager.InvalidToken ex) {
        LOG.info("Unable to further renew token for user: " + tokenInfo.getTokenOwner()
            + ", token is invalid. " + ex.getMessage());
        // Remove invalid token
        tokenCache.removeToken(tokenInfo);
      } catch (Exception ex) {
        LOG.error("Unable to renew token for user: " + tokenInfo.getTokenOwner(), ex);
      }
    }
  }

  private void renewDelegationToken(TokenInfo tokenInfo) throws IOException, InterruptedException {
    final long renewalTime = System.currentTimeMillis();

    UserGroupInformation.getLoginUser().doAs(new PrivilegedExceptionAction<Long>() {
      @Override
      public Long run() throws Exception {
        long tokenExpirationDate = tokenInfo.getToken().renew(conf);
        tokenInfo.setExpirationDate(tokenExpirationDate, renewalTime);
        return tokenExpirationDate;
      }
    });
  }
}
