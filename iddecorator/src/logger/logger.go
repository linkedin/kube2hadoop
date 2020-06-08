/**
 * Copyright 2020 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package logger

import (
"encoding/json"
"go.uber.org/zap"
)

type LoggerI interface {
	Fatalf(format string, v ...interface{})
	Panicf(format string, v ...interface{})
	Debugf(format string, v ...interface{})
	Infof(format string, v ...interface{})
	Errorf(format string, v ...interface{})
}

// setupLogging sets a new zap logger
func SetupLogging() (*zap.Logger, zap.Config, error) {
	rawJSON := []byte(`{
		"level": "info",
		"outputPaths": ["stdout"],
		"errorOutputPaths": ["stderr"],
		"encoding": "console",
		"encoderConfig": {
			"messageKey": "message",
			"levelKey": "level",
			"levelEncoder": "lowercase"
		}
	}`)
	var cfg zap.Config
	// TODO: Why do we need this?
	if err := json.Unmarshal(rawJSON, &cfg); err != nil {
		panic(err)
	}
	logger, err := cfg.Build()
	if err != nil {
		panic(err)
	}
	return logger, cfg, err
}
