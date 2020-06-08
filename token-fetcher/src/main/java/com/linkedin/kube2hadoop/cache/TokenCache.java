/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.cache;

import java.util.List;


public interface TokenCache {
  List<TokenInfo> getTokensForRenewal();
  void addToken(TokenInfo tokenInfo);
  void removeToken(TokenInfo tokenInfo);
}
