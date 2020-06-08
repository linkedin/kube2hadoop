/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.cache;

import com.linkedin.kube2hadoop.core.Constants;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.hadoop.security.token.Token;


/**
 * Wrapper class for {@code Token} class. Keeps track of expiration date of the token
 */
public class TokenInfo {
  private Token token;
  private Map<String, String> identifiableInfo;
  private long expirationDate = 0L;
  private long lastRenewalDate = 0L;

  public TokenInfo(Map<String, String> identifiableInfo, Token token) {
    this.token = token;
    this.identifiableInfo = identifiableInfo;
  }


  public Token getToken() {
    return this.token;
  }

  /**
   * Renew token immediately after getting the token to get the expiration date;
   * then renew it when approaching 90% of the expiration date
   * @param now current time in millisecond
   * @return whether or not to renew the tokens
   */
  public boolean needsRenewal(long now) {
    return expirationDate == 0L
        || (expirationDate - lastRenewalDate) * 0.9 + lastRenewalDate < now;
  }

  /**
   * Keep track of the smallest expiration date across all tokens in a credential
   * during the same renewal time.
   * @param expirationDate expiration date in milliseconds
   * @param renewalDate time of the renewal in milliseconds
   */
  public void setExpirationDate(long expirationDate, long renewalDate) {
    if (renewalDate == this.lastRenewalDate) {
      if (expirationDate < this.expirationDate) {
        this.expirationDate = expirationDate;
      }
    } else if (expirationDate > renewalDate) {
        this.expirationDate = expirationDate;
        this.lastRenewalDate = renewalDate;
    }
  }


  public String getTokenOwner() {
    return identifiableInfo.getOrDefault(Constants.PROXY_USER, null);
  }

  public Map<String, String> getIdentifiableInfo() {
    return this.identifiableInfo;
  }

  public long getExpirationDate() {
    return this.expirationDate;
  }

  public long getLastRenewalDate() {
    return this.lastRenewalDate;
  }

  /**
   * Create a list of {@code TokenInfo} that contains tokens from the same job instance
   * @param identifiableInfo key value pairs of
   * @param tokens list of tokens belonging to the same job
   * @return {@code TokenInfo}
   */
  public static List<TokenInfo> getTokenInfos(Map<String, String> identifiableInfo, List<Token> tokens) {
    return tokens
        .stream()
        .map(x -> new TokenInfo(identifiableInfo, x))
        .collect(Collectors.toList());
  }


}
