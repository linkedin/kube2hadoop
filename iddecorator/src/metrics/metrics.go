/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package metrics
import (
	"github.com/prometheus/client_golang/prometheus"
)

const (
	errorlabel = "error"
)

var (
	badRequestErrorCounts = prometheus.NewCounterVec(prometheus.CounterOpts{
		Namespace: "admissionRequests",
		Subsystem: "server",
		Name:      "bad_requests_count",
		Help:      "The number of requests that generate an http bad request.",
	}, []string{})

	invalidContentTypeErrorCounts = prometheus.NewCounterVec(prometheus.CounterOpts{
		Namespace: "admissionRequests",
		Subsystem: "server",
		Name:      "invalid_content_type_count",
		Help:      "The number of requests with an invalid content type.",
	}, []string{})

	decodeErrorCounts = prometheus.NewCounterVec(prometheus.CounterOpts{
		Namespace: "admissionRequests",
		Subsystem: "server",
		Name:      "decode_error_count",
		Help:      "The number of request decode errors.",
	}, []string{})

	encodeErrorCounts = prometheus.NewCounterVec(prometheus.CounterOpts{
		Namespace: "admissionResponse",
		Subsystem: "server",
		Name:      "encode_error_count",
		Help:      "The number of errors occurred encoding the reponse.",
	}, []string{})

	responseWriteErrorCounts = prometheus.NewCounterVec(prometheus.CounterOpts{
		Namespace: "admissionResponse",
		Subsystem: "server",
		Name:      "response_write_error_count",
		Help:      "The number of errors occurred writing the reponse.",
	}, []string{})

	successCounts = prometheus.NewCounterVec(prometheus.CounterOpts{
		Namespace: "admissionResponse",
		Subsystem: "server",
		Name:      "success_response_count",
		Help:      "The number of successful admission responses.",
	}, []string{})

	inFlightGauge = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "in_flight_requests",
		Help: "A gauge of requests currently being served by the wrapped handler.",
    })

	counter = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "requests_total",
			Help: "A counter for requests to the wrapped handler.",
		},
		[]string{"code", "method"},
	)

	// duration is partitioned by the HTTP method and handler. It uses custom
	// buckets based on the expected request duration.
	duration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "request_duration_seconds",
			Help:    "A histogram of latencies for requests.",
			Buckets: []float64{.25, .5, 1, 2.5, 5, 10},
		},
		[]string{},
	)

	// responseSize has no labels, making it a zero-dimensional
	// ObserverVec.
	responseSize = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "response_size_bytes",
			Help:    "A histogram of response sizes for requests.",
			Buckets: []float64{200, 500, 900, 1500},
		},
		[]string{},
	)
)

func init() {
	prometheus.MustRegister(counter)
	prometheus.MustRegister(duration)
	prometheus.MustRegister(inFlightGauge)
	prometheus.MustRegister(badRequestErrorCounts)
	prometheus.MustRegister(invalidContentTypeErrorCounts)
	prometheus.MustRegister(decodeErrorCounts)
	prometheus.MustRegister(encodeErrorCounts)
	prometheus.MustRegister(responseWriteErrorCounts)
	prometheus.MustRegister(successCounts)
	prometheus.MustRegister(responseSize)
}

// MonitoringMetrics are counters for certificate signing related operations.
type MonitoringMetrics struct {
	Counter                       *prometheus.CounterVec
	Duration                      prometheus.ObserverVec
	InFlightGauge                 prometheus.Gauge
	BadRequestErrorCounts         prometheus.Counter
	InvalidContentTypeErrorCounts prometheus.Counter
	DecodeErrorCounts             prometheus.Counter
	EncodeErrorCounts             prometheus.Counter
	ResponseWriteErrorCounts      prometheus.Counter
	SuccessCounts                 prometheus.Counter
	ResponseSize                  prometheus.ObserverVec
}

// NewMonitoringMetrics creates a new MonitoringMetrics.
func NewMonitoringMetrics() MonitoringMetrics {
	return MonitoringMetrics{
		Counter:                       counter,
		Duration:                      duration,
		InFlightGauge:                 inFlightGauge,
		BadRequestErrorCounts:         badRequestErrorCounts.With(prometheus.Labels{}),
		InvalidContentTypeErrorCounts: invalidContentTypeErrorCounts.With(prometheus.Labels{}),
		DecodeErrorCounts:             decodeErrorCounts.With(prometheus.Labels{}),
		EncodeErrorCounts:             encodeErrorCounts.With(prometheus.Labels{}),
		ResponseWriteErrorCounts:      responseWriteErrorCounts.With(prometheus.Labels{}),
		SuccessCounts:                 successCounts.With(prometheus.Labels{}),
		ResponseSize:                  responseSize,
	}
}