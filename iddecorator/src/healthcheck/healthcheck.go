/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package healthcheck

import (
	"context"
	"errors"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"
)

// Check is a health/readiness check.
type Check func() error

// Handler is an http.Handler with additional methods that register health and
// readiness checks. It handles handle "/live" and "/ready" HTTP
// endpoints.
type Handler interface {
	// The Handler is an http.Handler, so it can be exposed directly and handle
	// /live and /ready endpoints.
	http.Handler

	// AddLivenessCheck adds a check that indicates that this instance of the
	// application should be destroyed or restarted. A failed liveness check
	// indicates that this instance is unhealthy, not some upstream dependency.
	// Every liveness check is also included as a readiness check.
	AddLivenessCheck(name string, check Check)

	// AddReadinessCheck adds a check that indicates that this instance of the
	// application is currently unable to serve requests because of an upstream
	// or some transient failure. If a readiness check fails, this instance
	// should no longer receiver requests, but should not be restarted or
	// destroyed.
	AddReadinessCheck(name string, check Check)

	// LiveEndpoint is the HTTP handler for just the /live endpoint, which is
	// useful if you need to attach it into your own HTTP handler tree.
	LiveEndpoint(http.ResponseWriter, *http.Request)

	// ReadyEndpoint is the HTTP handler for just the /ready endpoint, which is
	// useful if you need to attach it into your own HTTP handler tree.
	ReadyEndpoint(http.ResponseWriter, *http.Request)
}

// TCPDialCheck returns a Check that checks TCP connectivity to the provided
// endpoint.
func TCPDialCheck(addr string, timeout time.Duration) Check {
	return func() error {
		conn, err := net.DialTimeout("tcp", addr, timeout)
		if err != nil {
			return err
		}
		return conn.Close()
	}
}

// HTTPGetCheck returns a Check that performs an HTTP GET request against the
// specified URL. The check fails if the response times out or returns a non-200
// status code.
func HTTPGetCheck(url string, timeout time.Duration) Check {
	client := http.Client{
		Timeout: timeout,
		// never follow redirects
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return func() error {
		resp, err := client.Get(url)
		if err != nil {
			return err
		}
		resp.Body.Close()
		if resp.StatusCode != 200 {
			return fmt.Errorf("returned status %d", resp.StatusCode)
		}
		return nil
	}

}

// basicHandler is a basic Handler implementation.
type basicHandler struct {
	http.ServeMux
	checksMutex     sync.RWMutex
	livenessChecks  map[string]Check
	readinessChecks map[string]Check
}

// NewHandler creates a new basic Handler
func NewHandler() Handler {
	h := &basicHandler{
		livenessChecks:  make(map[string]Check),
		readinessChecks: make(map[string]Check),
	}
	h.Handle("/live", http.HandlerFunc(h.LiveEndpoint))
	h.Handle("/ready", http.HandlerFunc(h.ReadyEndpoint))
	return h
}

func (s *basicHandler) LiveEndpoint(w http.ResponseWriter, r *http.Request) {
	s.handle(w, r, s.livenessChecks)
}

func (s *basicHandler) ReadyEndpoint(w http.ResponseWriter, r *http.Request) {
	s.handle(w, r, s.readinessChecks, s.livenessChecks)
}

func (s *basicHandler) AddLivenessCheck(name string, check Check) {
	s.checksMutex.Lock()
	defer s.checksMutex.Unlock()
	s.livenessChecks[name] = check
}

func (s *basicHandler) AddReadinessCheck(name string, check Check) {
	s.checksMutex.Lock()
	defer s.checksMutex.Unlock()
	s.readinessChecks[name] = check
}

func (s *basicHandler) collectChecks(checks map[string]Check, resultsOut map[string]string, statusOut *int) {
	s.checksMutex.RLock()
	defer s.checksMutex.RUnlock()
	for name, check := range checks {
		if err := check(); err != nil {
			*statusOut = http.StatusServiceUnavailable
			resultsOut[name] = err.Error()
		} else {
			resultsOut[name] = "OK"
		}
	}
}

func (s *basicHandler) handle(w http.ResponseWriter, r *http.Request, checks ...map[string]Check) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	checkResults := make(map[string]string)
	status := http.StatusOK
	for _, checks := range checks {
		s.collectChecks(checks, checkResults, &status)
	}

	// write out the response code and content type header
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)

	// unless ?full=1, return an empty body. Kubernetes only cares about the
	// HTTP status code, so we won't waste bytes on the full body.
	//TODO: change to "True"
	if r.URL.Query().Get("full") != "1" {
		w.Write([]byte("{}\n"))
		return
	}

	// otherwise, write the JSON body ignoring any encoding errors (which
	// shouldn't really be possible since we're encoding a map[string]string).
	encoder := json.NewEncoder(w)
	encoder.SetIndent("", "    ")
	encoder.Encode(checkResults)
}

// ErrNoData is returned if the first call of an Async() wrapped Check has not
// yet returned.
var ErrNoData = errors.New("no data yet")

// Async converts a Check into an asynchronous check that runs in a background
// goroutine at a fixed interval. The check is called at a fixed rate, not with
// a fixed delay between invocations. If your check takes longer than the
// interval to execute, the next execution will happen immediately.
//
// Note: if you need to clean up the background goroutine, use AsyncWithContext().
func Async(check Check, interval time.Duration) Check {
	return AsyncWithContext(context.Background(), check, interval)
}

// AsyncWithContext converts a Check into an asynchronous check that runs in a
// background goroutine at a fixed interval. The check is called at a fixed
// rate, not with a fixed delay between invocations. If your check takes longer
// than the interval to execute, the next execution will happen immediately.
//
// Note: if you don't need to cancel execution (because this runs forever), use Async()
func AsyncWithContext(ctx context.Context, check Check, interval time.Duration) Check {
	// create a chan that will buffer the most recent check result
	result := make(chan error, 1)

	// fill it with ErrNoData so we'll start in an initially failing state
	// (we don't want to be ready/live until we've actually executed the check
	// once, but that might be slow).
	result <- ErrNoData

	// make a wrapper that runs the check, and swaps out the current head of
	// the channel with the latest result
	update := func() {
		err := check()
		<-result
		result <- err
	}

	// spawn a background goroutine to run the check
	go func() {
		// call once right away (time.Tick() doesn't always tick immediately
		// but we want an initial result as soon as possible)
		update()

		// loop forever or until the context is canceled
		ticker := time.Tick(interval)
		for {
			select {
			case <-ticker:
				update()
			case <-ctx.Done():
				return
			}
		}
	}()

	// return a Check function that closes over our result and mutex
	return func() error {
		// peek at the head of the channel, then put it back
		err := <-result
		result <- err
		return err
	}
}