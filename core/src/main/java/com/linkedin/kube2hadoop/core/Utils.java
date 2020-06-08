/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.StringWriter;


public class Utils {
  private static final String STATUS = "status";
  private static final String STATUS_INTERNAL_SERVER_ERROR = "{ \"status\": \"internal_server_error\"}";

  private Utils() {

  }

  public static String genStatusJsonString(String respStatus) {
    try {
      return genJsonString(STATUS, respStatus);
    } catch (IOException e) {
      return STATUS_INTERNAL_SERVER_ERROR;
    }
  }

  public static String genJsonString(String key, String value) throws IOException {
    StringWriter sw = new StringWriter();
    JsonFactory factory = new JsonFactory();

    JsonGenerator generator = factory.createGenerator(sw);
    generator.writeStartObject();
    generator.writeStringField(key, value);
    generator.writeEndObject();
    generator.close();

    return sw.toString();
  }
}
