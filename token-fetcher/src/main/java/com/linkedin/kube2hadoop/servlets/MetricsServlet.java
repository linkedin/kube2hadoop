/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */

package com.linkedin.kube2hadoop.servlets;

//import com.linkedin.kube2hadoop.core.metrics.PrometheusMetrics;
//import com.linkedin.kube2hadoop.core.metrics.ServletMetric;
import com.linkedin.kube2hadoop.core.Utils;
import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class MetricsServlet extends HttpServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // TODO:  display collected metrics in Prometheus format
    response.setContentType("application/json");
    response.setStatus(HttpServletResponse.SC_OK);
    response.getWriter().println(Utils.genStatusJsonString("ok"));
  }

//  private void registerMetricsByClass(Class metricClass) {
//    ServletMetric metrics;
//  }

  // TODO: Collect metrics and display in Prometheus format
  private static String getPrometheusMetrics() {
//    ServletMetric metrics = GetDelegationTokenServlet.getMetrics();
//    PrometheusMetrics prometheusMetrics = new PrometheusMetrics();
//    prometheusMetrics.updateMetrics();

//    return prometheusMetrics.toString();
    return null;
  }
}
