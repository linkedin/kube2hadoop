/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.conf;

import com.linkedin.kube2hadoop.cache.TokenInfo;
import java.util.HashMap;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


public class TestTokenInfo {
  TokenInfo tokenInfo;

  @BeforeMethod
  public void setup() {
    tokenInfo = new TokenInfo(new HashMap<>(), null);
  }

  @Test
  public void testSetExpirationDateForNewToken() {
    long expirationDate = 1567186800000L;
    long now = 1567100400000L;
    tokenInfo.setExpirationDate(expirationDate, now);
    Assert.assertEquals(tokenInfo.getExpirationDate(), expirationDate);
    Assert.assertEquals(tokenInfo.getLastRenewalDate(), now);
  }

  @Test
  public void testSetExpirationDateForNewerExpirationDate() {
    long earlierExpirationDate = 1567186800000L;
    long laterExpirationDate = 1567196800000L;
    long now = 1567100400000L;

    tokenInfo.setExpirationDate(earlierExpirationDate, now);
    tokenInfo.setExpirationDate(laterExpirationDate, now);
    Assert.assertEquals(tokenInfo.getExpirationDate(), earlierExpirationDate);
    Assert.assertEquals(tokenInfo.getLastRenewalDate(), now);
  }

  @Test
  public void testSetExpirationDateTwice() {
    long earlierExpirationDate = 1567186800000L;
    long laterExpirationDate = 1567196800000L;
    long firstRenewalDate = 1567100400000L;
    long secondRenewalDate = 1567100500000L;

    tokenInfo.setExpirationDate(earlierExpirationDate, firstRenewalDate);
    tokenInfo.setExpirationDate(laterExpirationDate, secondRenewalDate);
    Assert.assertEquals(tokenInfo.getExpirationDate(), laterExpirationDate);
    Assert.assertEquals(tokenInfo.getLastRenewalDate(), secondRenewalDate);
  }

  @Test
  public void testNeedRenewal() {
    long expirationDate = 1567186800000L;
    long renewalDate = 1567100400000L;
    long now = 1567179000000L;
    long future = 1567186900000L;

    Assert.assertTrue(tokenInfo.needsRenewal(now));
    tokenInfo.setExpirationDate(expirationDate, renewalDate);
    Assert.assertTrue(tokenInfo.needsRenewal(now));
    Assert.assertTrue(tokenInfo.needsRenewal(future));
  }

  @Test
  public void testDontNeedRenewal() {
    long expirationDate = 1567186800000L;
    long renewalDate = 1567100400000L;
    long now = 1567100500000L;
    long notYet = 1567170000000L;

    tokenInfo.setExpirationDate(expirationDate, renewalDate);
    Assert.assertFalse(tokenInfo.needsRenewal(now));
    Assert.assertFalse(tokenInfo.needsRenewal(notYet));
  }
}
