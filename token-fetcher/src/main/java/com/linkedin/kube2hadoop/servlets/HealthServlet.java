/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.servlets;

import com.linkedin.kube2hadoop.core.TokenServiceException;
import com.linkedin.kube2hadoop.core.Utils;
import com.linkedin.kube2hadoop.service.TokenFetcherService;
import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;
import org.apache.hadoop.conf.Configuration;


/**
 * Health check for the server, used as liveliness probe for Kubernetes
 */
public class HealthServlet extends HttpServlet {
  private final Configuration conf;

  public HealthServlet(final Configuration conf) {
    this.conf = conf;
  }

  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // The first call to initialize TokenFetcherService takes a few seconds
    try {
      TokenFetcherService tfsInstance = TokenFetcherService.getInstance(conf);

      response.setContentType(MediaType.APPLICATION_JSON);
      response.setStatus(HttpServletResponse.SC_OK);
      response.getWriter().println(Utils.genStatusJsonString("ok"));
    } catch (TokenServiceException tse) {
      response.setContentType(MediaType.APPLICATION_JSON);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      response.getWriter().println(Utils.genStatusJsonString("internal_server_error"));
    }
  }
}
