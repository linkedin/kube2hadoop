/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.cache;

import com.linkedin.kube2hadoop.core.conf.ConfigurationKeys;
import com.linkedin.kube2hadoop.service.TokenRenewalService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import org.apache.hadoop.conf.Configuration;


/**
 * In memory cache local to the TokenFetcher instance.
 * This is responsible of creating/scheduling token renewal service
 */
public class LocalTokenCache implements TokenCache {
  private Configuration conf;
  private List<TokenInfo> tokensCache;
  private Timer renewerTimer;

  public LocalTokenCache(Configuration conf) {
    super();
    this.conf = conf;
    tokensCache = Collections.synchronizedList(new ArrayList<>());

    // Launched a timed task to renew tokens in the background
    long tokenRenewalInterval = conf.getLong(ConfigurationKeys.KUBE2HADOOP_TOKEN_RENEWAL_INTERVAL_IN_MILLISECONDS,
        ConfigurationKeys.DEFAULT_KUBE2HADOOP_TOKEN_RENEWAL_INTERVAL_IN_MILLISECONDS);
    renewerTimer = new Timer(true);
    TokenRenewalService trs = new TokenRenewalService(this.conf, this);
    renewerTimer.scheduleAtFixedRate(trs, 0, tokenRenewalInterval);
  }

  public void stopRenewer() {
    renewerTimer.cancel();
    renewerTimer.purge();
  }

  /**
   * Retrieve a list of tokens needed for renewal at a specific time (now)
   * Note: Most of the time the Tokens inside a {@code TokenInfo} object should have the similar expiration date,
   *  however, if one token has a closer expiration date than others, all the tokens for that user will be renewed.
   * @return list of tokens for renewal
   */
  @Override
  public List<TokenInfo> getTokensForRenewal() {
    long now = System.currentTimeMillis();
    List<TokenInfo> tokensToRenew = new ArrayList<>();
    for (TokenInfo tokenInfo : tokensCache) {
      if (tokenInfo.needsRenewal(now)) {
        tokensToRenew.add(tokenInfo);
      }
    }

    return tokensToRenew;
  }

  @Override
  public void addToken(TokenInfo tokenInfo) {
    tokensCache.add(tokenInfo);
  }

  @Override
  public void removeToken(TokenInfo tokenInfo) {
    tokensCache.remove(tokenInfo);
  }
}
