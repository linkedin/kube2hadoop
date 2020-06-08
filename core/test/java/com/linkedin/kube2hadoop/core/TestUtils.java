/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.core;

import org.testng.Assert;
import org.testng.annotations.Test;


public class TestUtils {
  @Test
  public void testGenStatusJsonString() {
    String expectedJsonStr = "{\"status\":\"ok\"}";
    String actualJsonStr = Utils.genStatusJsonString("ok");

    Assert.assertEquals(actualJsonStr, expectedJsonStr);
  }
}